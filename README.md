# Minecraft LegacyConsole Proxy

Kotlin + Gradle で Minecraft LegacyConsole Edition 向け proxy を実装するためのプロジェクトです

- MLC -> この proxy -> Java Minecraft Server

## 前提環境

- JDK 17+

## 実行方法

### Gradle で実行
```powershell
./gradlew run
```

### IntelliJ IDEA で実行
- `src/main/kotlin/dev/kuwa/MLCProxy/MLCProxy.kt` の `main` を Run
- デバッグ時は同じ構成を Debug 実行

## 実装ステップ

-[ ] Netty の server bootstrap 実装
-[ ] upstream 接続 bootstrap 実装
-[ ] 双方向パケット転送実装
-[ ] プロトコル状態管理実装（handshake/login/play）
