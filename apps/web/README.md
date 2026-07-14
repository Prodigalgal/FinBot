# FinBot Web

React + TypeScript 管理台，只通过 `/api/v2` 和可恢复 SSE 与 Java 主系统通信。

```powershell
npm ci
npx tsc -b --clean
npm run build
npm run smoke:system
```

运行时 API 入口由同源反向代理提供。前端不得直接访问 PostgreSQL、Quant、代理控制面、AI 厂商或交易所，也不得持有任何 Secret。
