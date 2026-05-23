CREATE TABLE IF NOT EXISTS popular_phrases (
    id BIGSERIAL PRIMARY KEY,
    phrase VARCHAR(255) UNIQUE NOT NULL,
    vector TEXT NOT NULL,
    usage_count BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_sent_vacancies_user_vacancy ON sent_vacancies(user_id, vacancy_id);
CREATE INDEX IF NOT EXISTS idx_vector_store_vacancy_id ON vector_store((metadata->>'vacancyId'));
CREATE INDEX IF NOT EXISTS idx_popular_phrases_usage ON popular_phrases(usage_count DESC);

ALTER TABLE vacancies ADD COLUMN IF NOT EXISTS embedding_retries INT DEFAULT 0;
ALTER TABLE vacancies ADD COLUMN content_hash VARCHAR(64);
CREATE INDEX idx_vacancies_site_content_hash ON vacancies(site_name, content_hash);