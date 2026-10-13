# IntelliTutor Web

正式桌面端 React 应用。页面只包含首页、AI 导师、我的、登录/注册和错误页，直接调用 Spring Boot `/api/v1` 接口。

```bash
npm ci
npm run dev
npm run typecheck
npm run lint
npm test
npm run build
```

本地开发默认把 `/api` 代理到 `http://localhost:8090`。生产构建固定使用同域 `/api`，图片输入能力通过根目录 `IMAGE_INPUT_ENABLED` 在 Spring Boot 运行时控制；修改该值不需要重新构建本应用。
