# MessBaaS

MessBaaS is a single-node high-frequency chat server built on plain Netty, raw JDBC, MySQL, and native WebSocket delivery.

## Architecture

- Netty handles HTTP APIs and WebSocket upgrades directly.
- JDBC repositories persist users, channels, and messages with raw SQL.
- Each channel keeps a bounded in-memory hot buffer of recent messages for fast history reads.
- Messages are written through to MySQL, appended to the hot buffer, and then broadcast in-process to subscribed websocket channels.

## Tech Stack

- Java 21
- Netty
- Raw JDBC
- HikariCP
- Flyway
- Jackson
- MySQL
- Docker

## Run

### Prerequisites

- Docker
- Docker Compose
- JDK 21

### 1. Start MySQL

```bash
docker-compose up -d
```

### 2. Run the server

```bash
bash ./gradlew run
```

### 3. WebSocket endpoint

- Native WebSocket endpoint: `/ws/channels?channelId={channelId}`
- Outbound payload: JSON `MessageEvent`

### 4. Hot buffer tuning

- `message.hotBufferPerChannel` controls how many recent messages are retained in memory per channel.
- Recent history requests are served from memory first and fall back to MySQL when the requested window is older than the hot buffer.
