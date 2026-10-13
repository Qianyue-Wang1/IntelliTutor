"""
Neo4j 知识图谱查询与更新服务
提供知识点掌握度查询、相关知识点查询、掌握度更新等功能
"""
import json
import os
from neo4j import GraphDatabase

NEO4J_URI = os.getenv("NEO4J_URI", "neo4j://localhost:7687")
NEO4J_USERNAME = os.getenv("NEO4J_USERNAME") or os.getenv("NEO4J_USER", "neo4j")
NEO4J_PASSWORD = os.getenv("NEO4J_PASSWORD", "")
NEO4J_DATABASE = os.getenv("NEO4J_DATABASE", "intelliTutor")


def _get_driver():
    return GraphDatabase.driver(NEO4J_URI, auth=(NEO4J_USERNAME, NEO4J_PASSWORD))


def _parse_exercise_records(raw):
    """安全解析 exercise_records 字段"""
    if isinstance(raw, list):
        return raw
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, list) else []
        except json.JSONDecodeError:
            return []
    return []


def query_knowledge_points_mastery(kp_ids: list, user_id: str = "default") -> list:
    """
    查询指定知识点ID的掌握度和做题记录

    参数:
        kp_ids: 知识点ID列表
        user_id: 用户ID
    返回:
        list[dict]，每个元素包含 id, description, mastery, exercise_records, domain, topic, sub_topic
    """
    if not kp_ids:
        return []
    driver = _get_driver()
    results = []
    try:
        with driver.session(database=NEO4J_DATABASE) as session:
            records = session.run(
                """
                MATCH (kp:KnowledgePoint)
                WHERE kp.id IN $kp_ids AND kp.user_id = $user_id
                RETURN kp.id AS id, kp.description AS description,
                       kp.mastery AS mastery, kp.exercise_records AS exercise_records,
                       kp.domain AS domain, kp.topic AS topic, kp.sub_topic AS sub_topic
                """,
                kp_ids=kp_ids, user_id=user_id
            )
            for record in records:
                results.append({
                    "id": record["id"],
                    "description": record["description"],
                    "mastery": record["mastery"] if record["mastery"] is not None else 0.5,
                    "exercise_records": _parse_exercise_records(record["exercise_records"]),
                    "domain": record["domain"],
                    "topic": record["topic"],
                    "sub_topic": record["sub_topic"],
                })
    except Exception as e:
        print(f"[Neo4j] 查询知识点掌握度失败: {e}")
    finally:
        driver.close()
    return results


def query_related_knowledge_points(kp_ids: list, user_id: str = "default") -> list:
    """
    查询与给定知识点同属一个子领域的相关知识点（不含给定的知识点）

    参数:
        kp_ids: 当前知识点ID列表
        user_id: 用户ID
    返回:
        list[dict]，每个元素包含 id, description, mastery, domain, topic, sub_topic
    """
    if not kp_ids:
        return []
    driver = _get_driver()
    results = []
    try:
        with driver.session(database=NEO4J_DATABASE) as session:
            records = session.run(
                """
                MATCH (kp:KnowledgePoint)
                WHERE kp.id IN $kp_ids AND kp.user_id = $user_id
                WITH COLLECT(DISTINCT kp.sub_topic) AS sub_topics
                MATCH (related:KnowledgePoint)
                WHERE related.sub_topic IN sub_topics AND related.user_id = $user_id
                  AND NOT related.id IN $kp_ids
                RETURN related.id AS id, related.description AS description,
                       related.mastery AS mastery, related.exercise_records AS exercise_records,
                       related.domain AS domain, related.topic AS topic, related.sub_topic AS sub_topic
                """,
                kp_ids=kp_ids, user_id=user_id
            )
            for record in records:
                results.append({
                    "id": record["id"],
                    "description": record["description"],
                    "mastery": record["mastery"] if record["mastery"] is not None else 0.5,
                    "exercise_records": _parse_exercise_records(record["exercise_records"]),
                    "domain": record["domain"],
                    "topic": record["topic"],
                    "sub_topic": record["sub_topic"],
                })
    except Exception as e:
        print(f"[Neo4j] 查询相关知识点失败: {e}")
    finally:
        driver.close()
    return results


def update_knowledge_points_mastery(updates: list, user_id: str = "default"):
    """
    更新知识点的掌握度和做题记录

    参数:
        updates: list[dict]，每个元素包含:
            - id: 知识点ID
            - mastery: 新的掌握度 (0-1)
            - exercise_records: 更新后的做题记录列表
        user_id: 用户ID
    """
    if not updates:
        return
    driver = _get_driver()
    try:
        with driver.session(database=NEO4J_DATABASE) as session:
            for update in updates:
                session.run(
                    """
                    MATCH (kp:KnowledgePoint {id: $kp_id, user_id: $user_id})
                    SET kp.mastery = $mastery,
                        kp.exercise_records = $exercise_records
                    """,
                    kp_id=update["id"],
                    user_id=user_id,
                    mastery=update["mastery"],
                    exercise_records=json.dumps(
                        update.get("exercise_records", []), ensure_ascii=False
                    ),
                )
        print(f"[Neo4j] 成功更新 {len(updates)} 个知识点的掌握度")
    except Exception as e:
        print(f"[Neo4j] 更新知识点掌握度失败: {e}")
    finally:
        driver.close()


def get_user_profile_from_graph(user_id: str = "default") -> dict:
    """
    从知识图谱中获取用户整体学习画像摘要

    返回:
        dict: 包含 total_kps, avg_mastery, weak_kps, strong_kps
    """
    driver = _get_driver()
    profile = {"total_kps": 0, "avg_mastery": 0.5, "weak_kps": [], "strong_kps": []}
    try:
        with driver.session(database=NEO4J_DATABASE) as session:
            result = session.run(
                """
                MATCH (kp:KnowledgePoint {user_id: $user_id})
                RETURN kp.id AS id, kp.description AS description,
                       kp.mastery AS mastery, kp.domain AS domain
                ORDER BY kp.mastery ASC
                """,
                user_id=user_id
            )
            all_kps = []
            for record in result:
                mastery = record["mastery"] if record["mastery"] is not None else 0.5
                all_kps.append({
                    "id": record["id"],
                    "description": record["description"],
                    "mastery": mastery,
                    "domain": record["domain"],
                })

            if all_kps:
                profile["total_kps"] = len(all_kps)
                profile["avg_mastery"] = sum(k["mastery"] for k in all_kps) / len(all_kps)
                profile["weak_kps"] = [k for k in all_kps if k["mastery"] < 0.4][:5]
                profile["strong_kps"] = [k for k in all_kps if k["mastery"] >= 0.7][-5:]
    except Exception as e:
        print(f"[Neo4j] 获取用户画像失败: {e}")
    finally:
        driver.close()
    return profile


def create_user_knowledge_points(user_id: str) -> int:
    """
    为指定用户在知识图谱中创建全量知识点节点，并与已有的 SubTopic 建立关系。
    若该用户的知识点已存在则跳过（MERGE语义），不重置掌握度。

    参数:
        user_id: 用户ID
    返回:
        int: 成功创建/合并的知识点数量
    """
    kc_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "KT", "ours", "data", "kc_data.json",
    )
    with open(kc_path, "r", encoding="utf-8") as f:
        kc_data = json.load(f)

    knowledge_points = kc_data.get("knowledge_points", [])
    if not knowledge_points:
        print(f"[Neo4j] kc_data.json 中未找到知识点数据")
        return 0

    driver = _get_driver()
    count = 0
    try:
        with driver.session(database=NEO4J_DATABASE) as session:

            # 检查用户是否已有知识图谱，若存在则先删除
            result = session.run(
                "MATCH (kp:KnowledgePoint {user_id: $user_id}) RETURN count(kp) AS cnt",
                user_id=user_id,
            )
            existing_cnt = result.single()["cnt"]
            if existing_cnt > 0:
                print(f"[Neo4j] 用户 {user_id} 已有知识图谱 ({existing_cnt} 个节点)，正在删除并重新初始化...")
                session.run(
                    "MATCH (kp:KnowledgePoint {user_id: $user_id}) DETACH DELETE kp",
                    user_id=user_id,
                )

            for kp in knowledge_points:
                session.run(
                    """
                    MATCH (st:SubTopic {name: $sub_topic, domain: $domain, topic: $topic})
                    MERGE (kp:KnowledgePoint {id: $id, user_id: $user_id})
                    ON CREATE SET
                        kp.description      = $description,
                        kp.domain           = $domain,
                        kp.topic            = $topic,
                        kp.sub_topic        = $sub_topic,
                        kp.mastery          = 0.2,
                        kp.exercise_records = $exercise_records
                    MERGE (st)-[:HAS_KNOWLEDGE_POINT]->(kp)
                    """,
                    id=kp["id"],
                    user_id=user_id,
                    description=kp["description"],
                    domain=kp["domain"],
                    topic=kp["topic"],
                    sub_topic=kp["sub_topic"],
                    exercise_records=json.dumps([], ensure_ascii=False),
                )
                count += 1
        print(f"[Neo4j] 用户 {user_id} 知识点创建完成，共 {count} 个")
    except Exception as e:
        print(f"[Neo4j] 创建用户知识点失败: {e}")
    finally:
        driver.close()
    return count
