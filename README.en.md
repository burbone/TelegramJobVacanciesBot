# JobBot

Telegram bot for IT job search with semantic search.

[Русская версия](README_JobBot.md)

---

## Stack

- **Spring AI** — LLM orchestration
- **Ollama** — local language model
- **PGVector** — vector store for semantic search
- **PostgreSQL** — primary database
- **Docker** — containerization

---

## Quick Start

**1. Prerequisites**

Make sure you have installed:
- [Docker](https://docs.docker.com/get-docker/)
- [PostgreSQL](https://www.postgresql.org/download/)
- [Ollama](https://ollama.com/download)

**2. Configuration**

```bash
cp .env.example .env
```

Fill in `.env`:

| Variable | Description |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather) |
| `DB_PASSWORD` | PostgreSQL password |
| `MTS_API_KEY` | MTS API key |

**3. Run**

```bash
./mvnw spring-boot:run
```

---

## Roadmap

- [ ] Microservice architecture
- [ ] Apache Kafka for event streaming
- [ ] Cryptocurrency payments
