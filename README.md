# JobBot

Telegram-бот для поиска IT-вакансий с семантическим поиском.

[English version](README.en.md)

---

## Стек

- **Spring AI** — оркестрация LLM-запросов
- **Ollama** — локальная языковая модель
- **PGVector** — векторное хранилище для семантического поиска
- **PostgreSQL** — основная БД
- **Docker** — контейнеризация

---

## Быстрый старт

**1. Зависимости**

Убедись, что установлены:
- [Docker](https://docs.docker.com/get-docker/)
- [PostgreSQL](https://www.postgresql.org/download/)
- [Ollama](https://ollama.com/download)

**2. Конфигурация**

```bash
cp .env.example .env
```

Заполни `.env`:

| Переменная | Описание |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Токен бота от [@BotFather](https://t.me/BotFather) |
| `DB_PASSWORD` | Пароль PostgreSQL |
| `MTS_API_KEY` | Ключ MTS API |

**3. Запуск**

```bash
./mvnw spring-boot:run
```

---

## Roadmap

- [ ] Микросервисная архитектура
- [ ] Apache Kafka для событийной шины
- [ ] Оплата криптовалютой
