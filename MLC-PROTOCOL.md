# MLC Protocol Design (Minecraft Legacy Console Proxy)

このドキュメントは、`tmp/MinecraftConsoles` の実装調査結果を元に、Proxy 実装で必要なプロトコル設計を整理したものです。

## 1. スコープ

- 対象: Minecraft Legacy Console Edition TU19 系 (`MinecraftConsoles` ベース)
- 対象層: ゲーム層パケット (`Packet` / `Connection` / `ClientConnection` / `PendingConnection` / `PlayerConnection`)
- 非対象: 画面/UI、ゲームロジック詳細、全プラットフォーム固有の外部SDK挙動

## 2. バージョン定義

- Netcode Version (セッション互換): `MINECRAFT_NET_VERSION = VER_NETWORK = 560`
- Game Protocol Version (Login互換): `SharedConstants::NETWORK_PROTOCOL_VERSION = 78`

注意:
- `PreLogin(2)` では `m_netcodeVersion` (560) を検証
- `Login(1)` では `clientVersion` (78) を検証
- どちらか不一致で `OutdatedServer/OutdatedClient` 切断

## 3. フレーミングと基本エンコーディング

### 3.1 ゲーム層フレーミング

- 基本形: `packetId(1 byte) + packetPayload`
- ゲーム層共通 Length フィールドはなし
- バイトオーダー: `DataInputStream/DataOutputStream` 準拠で Big Endian

### 3.2 文字列エンコーディングは2系統

- `Packet::readUtf/writeUtf`
  - `short length` + `wchar` 配列
  - 2byte文字単位の長さ
  - 主に古い Packet 実装で使用
- `DataInputStream::readUTF / DataOutputStream::writeUTF`
  - Java modified UTF-8 形式
  - `unsigned short` の「バイト長」先頭
  - Texture系や CustomPayload で多用

Proxy 実装要件:
- Packetごとにどちらを使うかを厳密に分けること

### 3.3 PlayerUID エンコーディングはプラットフォーム分岐

- Sony系: 構造体生バイト列
- Durango: UTF文字列
- その他: 64bit (`readLong/writeLong`)

Proxy 実装要件:
- 対向クライアント/ホストの実装系を前提に Codec を切り替えること

## 4. 接続アーキテクチャ

- `Socket`:
  - local loopback キュー or network キューへのバイト配送
  - `pushDataToQueue()` で受信バイト列を入力キューへ積む
- `Connection`:
  - read thread / write thread
  - `incoming` / `outgoing` / `outgoing_slow`
  - `tick()` で `incoming` を `PacketListener` へ配送
- Listener:
  - 受信側ロールにより `isServerPacketListener()` を切替
  - サーバ側: `PendingConnection` -> `PlayerConnection`
  - クライアント側: `ClientConnection`

## 5. セッション状態遷移

推奨状態機械:

1. `CONNECTED`
2. `PRELOGIN_SENT`
3. `PRELOGIN_OK`
4. `LOGIN_SENT`
5. `LOGIN_OK`
6. `PLAY`
7. `CLOSED`

### 5.1 ハンドシェイク時系列

1. Client -> Server: `PreLoginPacket(2)`
2. Server: `m_netcodeVersion` を検証
3. Server -> Client: `PreLoginPacket(2)` 応答
4. Client: UGC/フレンド条件/TexturePack 条件を評価
5. Client -> Server: `LoginPacket(1)`
6. Server: `NETWORK_PROTOCOL_VERSION` を検証
7. Server: `placeNewPlayer()` で `Login/Spawn/Abilities/HeldItem/Time` 送信
8. Client: `handleLogin()` で world/player 初期化
9. Server -> Client: `MovePlayer(10/11/12/13)` 同期
10. Client: 最初の `handleMovePlayer` 受信で `started = true` (PLAY移行)

## 6. 受信方向制約

`Packet::map(id, receiveOnClient, receiveOnServer, sendToAnyClient, ...)` に基づく。

- `C2S only` (14 IDs):
  - `7,14,15,19,27,102,108,150,151,152,159,167,205,254`
- `S2C only` (56 IDs):
  - `4,5,6,8,17,20,22,23,24,25,26,28,29,30,31,32,33,34,35,38,39,40,41,42,43,44,50,51,52,53,54,55,60,61,62,63,70,71,100,104,105,131,132,133,155,156,158,162,163,164,165,200,206,207,208,209`
- `双方向` (26 IDs):
  - `0,1,2,3,9,10,11,12,13,16,18,101,103,106,107,130,153,154,157,160,161,166,201,202,250,255`

不正方向の受信は `Bad packet id` 扱い。

## 7. Packet ID 設計方針

- 定義総数: `96` unique IDs (`0..255` sparse)
- 旧Java系 packet + 4J 拡張 packet が混在
- 実運用では以下を最優先実装対象にする

### 7.1 接続必須

- `0 KeepAlive`
- `1 Login`
- `2 PreLogin`
- `255 Disconnect`

### 7.2 プレイ必須（最小）

- `10/11/12/13 MovePlayer`
- `20 AddPlayer`
- `23/24 AddEntity/AddMob`
- `29 RemoveEntities`
- `31/32/33 MoveEntity*`
- `34 TeleportEntity`
- `50 ChunkVisibility`
- `51 BlockRegionUpdate`
- `52 ChunkTilesUpdate`
- `53 TileUpdate`
- `54 TileEvent`

### 7.3 LCE/4J固有で重要

- `153 ServerSettingsChanged`
- `154 TexturePacket`
- `157 TextureChangePacket`
- `160 TextureAndGeometryPacket`
- `161 TextureAndGeometryChangePacket`
- `166 XZPacket`
- `201 PlayerInfoPacket` (4Jで再用途化)

## 8. 主要パケット仕様

## 8.1 KeepAlive (0)

- payload: `int id`
- クライアントは受信 `id` をそのまま返信
- サーバ側は `lastKeepAliveId` と突合して RTT 更新

## 8.2 PreLogin (2)

payload 順:

1. `short m_netcodeVersion`
2. `Packet::Utf loginKey`
3. `byte m_friendsOnlyBits`
4. `int m_ugcPlayersVersion`
5. `byte m_dwPlayerCount`
6. `PlayerUID[m_dwPlayerCount]`
7. `byte[14] m_szUniqueSaveName`
8. `int m_serverSettings`
9. `byte m_hostIndex`
10. `int m_texturePackId`

補足:
- `write()` は先頭で `MINECRAFT_NET_VERSION` を固定出力

## 8.3 Login (1)

payload 順:

1. `int clientVersion`
2. `Packet::Utf userName`
3. `Packet::Utf levelTypeName`
4. `long seed`
5. `int gameType`
6. `byte dimension`
7. `byte mapHeight`
8. `byte maxPlayers`
9. `PlayerUID offlineXuid`
10. `PlayerUID onlineXuid`
11. `bool m_friendsOnlyUGC`
12. `int m_ugcPlayersVersion`
13. `byte difficulty`
14. `int m_multiplayerInstanceId`
15. `byte m_playerIndex`
16. `int m_playerSkinId`
17. `int m_playerCapeId`
18. `bool m_isGuest`
19. `bool m_newSeaLevel`
20. `int m_uiGamePrivileges`
21. `short m_xzSize` (`_LARGE_WORLDS` のみ)
22. `byte m_hellScale` (`_LARGE_WORLDS` のみ)

## 8.4 Disconnect (255)

- payload: `int reason`
- 理由コードは `eDisconnectReason` enum を使用

## 8.5 MovePlayer (10/11/12/13)

- 共通末尾: `byte flags`
  - bit0: `onGround`
  - bit1: `isFlying`
- `11 Pos`: `x,y,yView,z + flags`
- `12 Rot`: `yRot,xRot + flags`
- `13 PosRot`: `x,y,yView,z,yRot,xRot + flags`

## 8.6 MoveEntitySmall (162/163/164/165)

- entity id, yaw, delta xyz をビットパック
- `163 Pos` は `id+ya` を short 化 + `xa/za` nibble packing
- `165 PosRot` は `id+yRot` short + `xa/ya/za` short packing

Proxy 実装要件:
- 符号拡張とマスク処理を厳密に実装

## 8.7 BlockRegionUpdate (51)

- `shouldDelay = true` 既定
- Full chunk 時はブロック並び替え後に圧縮
- 圧縮: `CompressLZXRLE`
- `sizeAndLevel`:
  - 下位30bit: 圧縮サイズ
  - 上位2bit: `levelIdx` (dimension index)
- read 時は `DecompressLZXRLE`

## 8.8 ChunkTilesUpdate (52)

- `shouldDelay = true` 既定
- `countAndFlags`:
  - bit7: `dataAllZero`
  - bit5-6: `levelIdx`
  - bit0-4: `count`
- 同一 block type は再送省略する差分最適化あり

## 8.9 Texture 系 (154/157/160/161)

- `TexturePacket(154)`:
  - `textureName(UTF)` + `short size` + bytes
  - `size==0` はリクエスト、`size>0` はレスポンス
- `TextureAndGeometryPacket(160)`:
  - skin/cape texture + geometry boxes + anim override
- `TextureChange(157)`:
  - エンティティの skin/cape パス変更通知
- `TextureAndGeometryChange(161)`:
  - skin path + skinId 変更通知

設計意図:
- クライアント間で持っていない texture をホスト経由で配布

## 8.10 CustomPayload (250)

- `identifier(UTF)` + `short length` + raw bytes
- 代表チャネル:
  - `MC|TrList`, `MC|TrSel`, `MC|Beacon`, `MC|ItemName` 等

## 9. 送信キュー・優先度・遅延

- `Connection.send(packet)`:
  - `packet.shouldDelay == true` の場合は `outgoing_slow` へ
  - この時点で `shouldDelay` は false に落とされる
- `Connection.queueSend(packet)`:
  - `outgoing_slow` に投入し、`shouldDelay` を維持
- `writeTick()`:
  - `outgoing` は通常送信
  - `outgoing_slow` は条件により個別送信 (`writeWithFlags`)
  - Xbox 以外は `NON_QNET_SENDDATA_ACK_REQUIRED` を付けて送るケースあり

運用上の意味:
- 初期チャンクや重い更新を低優先/遅延で流して体感遅延を抑える

## 10. タイムアウトと切断条件

- 入力なし `MAX_TICKS_WITHOUT_INPUT = 20*60` tick で timeout 切断
- `estimatedRemaining > 1MB` で overflow 切断
- ソケット closing 検出で closed 切断

## 11. Split-screen / 同一筐体配信制御

- `sendToAnyClient` フラグが false の packet は、同一筐体全員へは送られない
- `PlayerConnection::send/queueSend` で `Packet::canSendToAnyClient()` を判定

Proxy 実装要件:
- 同一端末複数プレイヤーを扱うならこの概念を保持すること

## 12. トランスポート差分

- QNet/Sony 系:
  - 信頼・順序付き送信前提で payload をそのまま中継
  - 受信 callback で `socket->pushDataToQueue()`
- Windows64 LAN stub:
  - TCP で `4-byte length prefix + payload`
  - 受信側で長さ読みしてから payload をキュー投入

設計上の分離:
- 下層 transport の framing と、ゲーム packet framing を混同しない

## 13. Proxy 実装ガイド（推奨）

1. Decoder は必ずストリーム指向で実装する
2. Packet ID テーブルは `id -> {direction, parser}` の明示管理にする
3. 文字列 codec を `PacketUtf` と `DataUTF` で分離する
4. Handshake state machine を厳密に実装する
5. `PreLogin` と `Login` の version チェックを先に通す
6. `51/52/162-165` は専用テストで round-trip 検証する
7. Texture 系は `size==0` を request として扱う
8. Disconnect reason は enum として保持し、ログに reason 名を残す
9. Windows64 transport adapter は length-prefix を吸収する
10. 将来互換のため、未知 packet は state と方向が許せば透過転送できる構造にする

## 14. 実装時の注意点

- `isAync()/canHandleAsyncPackets()` は実質未活用で、処理分岐には使われていない
- 一部 `getEstimatedSize()` は厳密サイズでない
- `_LARGE_WORLDS` 条件で packet レイアウトが変わるものがある
- PlayerUID はプラットフォーム依存のため、単純固定化すると相互接続性を壊す

## 15. 付録: 方向別 Packet ID 一覧

### C2S only

`7,14,15,19,27,102,108,150,151,152,159,167,205,254`

### S2C only

`4,5,6,8,17,20,22,23,24,25,26,28,29,30,31,32,33,34,35,38,39,40,41,42,43,44,50,51,52,53,54,55,60,61,62,63,70,71,100,104,105,131,132,133,155,156,158,162,163,164,165,200,206,207,208,209`

### Bidirectional

`0,1,2,3,9,10,11,12,13,16,18,101,103,106,107,130,153,154,157,160,161,166,201,202,250,255`

