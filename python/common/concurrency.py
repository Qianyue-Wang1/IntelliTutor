"""One-process admission control and session serialization for teaching operations."""
import asyncio
import contextvars
import copy
import functools
import inspect
import os
import time
from concurrent.futures import ThreadPoolExecutor
from weakref import WeakKeyDictionary

from fastapi import HTTPException

_deadline = contextvars.ContextVar("inference_deadline", default=None)
_states = WeakKeyDictionary()


def operation_time_remaining():
    deadline = _deadline.get()
    return None if deadline is None else deadline - time.monotonic()


class _State:
    def __init__(self):
        workers = int(os.getenv("PYTHON_BLOCKING_WORKERS", "12"))
        capacity = int(os.getenv("PYTHON_MAX_OPERATIONS", "12"))
        if workers <= 0 or capacity <= 0:
            raise ValueError("Python worker and operation limits must be positive")
        self.executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="tutor")
        self.admission = asyncio.Semaphore(capacity)
        self.locks = {}
        self.active = set()
        self.initializing = {}


def _state():
    loop = asyncio.get_running_loop()
    if loop not in _states:
        _states[loop] = _State()
    return _states[loop]


def register_new_session(session_id, on_failure):
    """Hide partially initialized sessions from concurrent snapshots and mutations."""
    try:
        task = asyncio.current_task()
    except RuntimeError:
        return
    state = _state()
    if task in state.active:
        state.initializing[session_id] = (task, asyncio.Event(), on_failure)


async def run_blocking(function, *args, **kwargs):
    """Keep the enclosing session lock until a cancelled thread really finishes."""
    context = contextvars.copy_context()
    future = asyncio.get_running_loop().run_in_executor(
        _state().executor, context.run, functools.partial(function, *args, **kwargs))
    try:
        return await asyncio.shield(future)
    except asyncio.CancelledError:
        while not future.done():
            try:
                await asyncio.shield(future)
            except asyncio.CancelledError:
                continue
            except Exception:
                break
        if not future.cancelled():
            future.exception()
        raise


def session_operation(function):
    """Serialize each session; nested endpoint calls reuse their parent's protection."""
    signature = inspect.signature(function)

    @functools.wraps(function)
    async def wrapped(*args, **kwargs):
        state = _state()
        task = asyncio.current_task()
        if task in state.active:
            return await function(*args, **kwargs)
        bound = signature.bind(*args, **kwargs)
        request = bound.arguments.get("request")
        session_id = bound.arguments.get("session_id") or getattr(request, "session_id", None)
        seconds = int(os.getenv("PYTHON_OPERATION_TIMEOUT_SECONDS", "600"))
        if seconds <= 0:
            raise ValueError("PYTHON_OPERATION_TIMEOUT_SECONDS must be positive")
        entry = None
        if session_id:
            entry = state.locks.setdefault(session_id, [asyncio.Lock(), 0])
            entry[1] += 1
        token = _deadline.set(time.monotonic() + seconds)
        completed = False
        try:
            async with asyncio.timeout(seconds):
                initialization = state.initializing.get(session_id)
                if initialization and initialization[0] is not task:
                    await initialization[1].wait()
                if entry:
                    await entry[0].acquire()
                try:
                    async with state.admission:
                        state.active.add(task)
                        result = copy.deepcopy(await function(*args, **kwargs))
                        completed = True
                        return result
                finally:
                    if entry:
                        entry[0].release()
        except TimeoutError as exc:
            raise HTTPException(504, "推理操作超时，请稍后重试") from exc
        finally:
            _deadline.reset(token)
            state.active.discard(task)
            for key, (owner, ready, on_failure) in list(state.initializing.items()):
                if owner is task:
                    try:
                        if not completed:
                            on_failure()
                    finally:
                        ready.set()
                        state.initializing.pop(key, None)
            if entry:
                entry[1] -= 1
                if entry[1] == 0:
                    state.locks.pop(session_id, None)

    # FastAPI must resolve postponed annotations in the endpoint's source module.
    annotations = inspect.get_annotations(function, eval_str=True)
    wrapped.__signature__ = signature.replace(
        parameters=[p.replace(annotation=annotations.get(p.name, p.annotation))
                    for p in signature.parameters.values()],
        return_annotation=annotations.get("return", signature.return_annotation),
    )
    return wrapped


def startup():
    if int(os.getenv("PYTHON_OPERATION_TIMEOUT_SECONDS", "600")) <= 0:
        raise ValueError("PYTHON_OPERATION_TIMEOUT_SECONDS must be positive")
    _state()


async def shutdown():
    state = _states.pop(asyncio.get_running_loop(), None)
    if state:
        # Uvicorn drains requests before lifespan shutdown; blocking calls are awaited.
        state.executor.shutdown(wait=True, cancel_futures=True)
