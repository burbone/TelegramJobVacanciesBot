package com.botTelegram.botTelegram.service;

import com.botTelegram.botTelegram.domain.SearchMode;
import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.repository.VacancyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final String OLLAMA_URL = "http://127.0.0.1:11434/api/embed";
    private static final String OLLAMA_MODEL = "nomic-embed-text";
    private static final String QUERY_PREFIX = "search_query: ";
    private static final String DOCUMENT_PREFIX = "search_document: ";
    private static final int BATCH_SIZE = 50;
    private static final int THREAD_COUNT = 4;
    private static final int MAX_RETRIES = 5;
    private static final int[] FIBONACCI_DELAYS = {2, 3, 5, 8, 13, 21, 34};

    private final VacancyRepository vacancyRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ollamaBusy = new AtomicBoolean(false);
    private Thread embeddingThread;

    private final Map<Long, Integer> userDelayIndex = new ConcurrentHashMap<>();

    public boolean isRunning() {
        return running.get();
    }

    public void stopEmbedding() {
        if (running.get()) {
            log.info("Stopping embedding due to parsing start");
            running.set(false);
            if (embeddingThread != null) {
                embeddingThread.interrupt();
            }
        }
    }

    public void processUnembedded() {
        if (running.get()) {
            log.info("Embedding already running");
            return;
        }

        embeddingThread = new Thread(() -> {
            running.set(true);
            log.info("Starting embedding for unprocessed vacancies");
            try {
                processEmbeddingLoop();
            } finally {
                running.set(false);
            }
        });

        embeddingThread.setDaemon(true);
        embeddingThread.setName("embedding-thread");
        embeddingThread.start();
    }

    public void processUnembeddedSync() {
        if (running.get()) {
            log.info("Embedding already running, waiting for completion");
            while (running.get()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            return;
        }

        running.set(true);
        log.info("Synchronous embedding started");
        try {
            processEmbeddingLoop();
        } finally {
            running.set(false);
        }
    }

    private void processEmbeddingLoop() {
        try {
            List<Vacancy> unembedded = vacancyRepository.findByEmbeddingIdIsNull();
            log.info("Vacancies without embedding: {}", unembedded.size());

            List<List<Vacancy>> batches = Lists.partition(unembedded, BATCH_SIZE);
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            AtomicInteger count = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = batches.stream()
                    .map(batch -> CompletableFuture.runAsync(() -> {
                        if (!running.get() || Thread.currentThread().isInterrupted()) return;
                        processBatch(batch, count, unembedded.size());
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();

            log.info("Embedding completed: processed {}/{}", count.get(), unembedded.size());

            retryFailedEmbeddings();

        } catch (Exception e) {
            log.error("Embedding error: {}", e.getMessage());
        }
    }

    private void retryFailedEmbeddings() {
        List<Vacancy> retryCandidates = vacancyRepository.findByEmbeddingIdIsNull();
        if (retryCandidates.isEmpty()) return;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            final int currentAttempt = attempt; // делаем effectively final
            List<Vacancy> toProcess = retryCandidates.stream()
                    .filter(v -> v.getEmbeddingRetries() < currentAttempt)
                    .toList();
            if (toProcess.isEmpty()) break;

            log.info("Retry attempt {} for {} vacancies", currentAttempt, toProcess.size());
            List<List<Vacancy>> batches = Lists.partition(toProcess, BATCH_SIZE);
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            AtomicInteger successCount = new AtomicInteger(0);
            final AtomicInteger finalSuccessCount = successCount; // effectively final ссылка

            List<CompletableFuture<Void>> futures = batches.stream()
                    .map(batch -> CompletableFuture.runAsync(() -> {
                        processBatchWithRetry(batch, finalSuccessCount, currentAttempt);
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();

            log.info("Retry attempt {} completed, successful: {}", currentAttempt, finalSuccessCount.get());
        }
        List<Vacancy> stillFailed = vacancyRepository.findByEmbeddingIdIsNull().stream()
                .filter(v -> v.getEmbeddingRetries() >= MAX_RETRIES)
                .toList();
        if (!stillFailed.isEmpty()) {
            log.warn("Deleting {} vacancies after {} failed embedding attempts", stillFailed.size(), MAX_RETRIES);
            for (Vacancy v : stillFailed) {
                vacancyRepository.delete(v);
            }
        }
    }

    private void processBatch(List<Vacancy> batch, AtomicInteger count, int total) {
        processBatchInternal(batch, count, total, false);
    }

    private void processBatchWithRetry(List<Vacancy> batch, AtomicInteger count, int attempt) {
        processBatchInternal(batch, count, batch.size(), true);
    }

    private void processBatchInternal(List<Vacancy> batch, AtomicInteger count, int total, boolean isRetry) {
        long t1 = System.currentTimeMillis();

        try {
            List<String> allChunks = new ArrayList<>();
            List<int[]> chunkMap = new ArrayList<>();

            for (int v = 0; v < batch.size(); v++) {
                List<String> chunks = buildChunks(batch.get(v));
                for (int c = 0; c < chunks.size(); c++) {
                    allChunks.add(chunks.get(c));
                    chunkMap.add(new int[]{v, c});
                }
            }
            long t2 = System.currentTimeMillis();

            float[][] vectors = getEmbeddingBatch(
                    allChunks.stream()
                            .map(t -> DOCUMENT_PREFIX + t)
                            .toList()
            );
            long t3 = System.currentTimeMillis();

            if (vectors == null) {
                log.warn("Batch returned null vectors, skipping {} vacancies", batch.size());
                for (Vacancy vacancy : batch) {
                    vacancy.setEmbeddingRetries(vacancy.getEmbeddingRetries() + 1);
                    vacancyRepository.save(vacancy);
                }
                return;
            }

            for (int i = 0; i < vectors.length; i++) {
                int vacancyIdx = chunkMap.get(i)[0];
                int chunkIdx = chunkMap.get(i)[1];
                Vacancy vacancy = batch.get(vacancyIdx);

                String docId = UUID.randomUUID().toString();
                String metadata = String.format(
                        "{\"vacancyId\":\"%s\",\"siteName\":\"%s\",\"chunk\":%d}",
                        vacancy.getId(), vacancy.getSiteName(), chunkIdx
                );

                jdbcTemplate.update(
                        "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?::uuid, ?, ?::jsonb, ?::vector)",
                        docId, allChunks.get(i), metadata, vectorToString(vectors[i])
                );

                if (chunkIdx == 0) {
                    vacancy.setEmbeddingId(docId);
                    vacancy.setEmbeddingRetries(0);
                    vacancyRepository.save(vacancy);
                    int c = count.incrementAndGet();
                    if (c % 10 == 0) {
                        log.info("Embedding progress: {}/{}", c, total);
                    }
                }
            }
            long t4 = System.currentTimeMillis();
            log.info("Batch {}: build={}ms, ollama={}ms, db={}ms",
                    batch.size(), t2 - t1, t3 - t2, t4 - t3);

        } catch (Exception e) {
            log.error("Batch processing error: {}", e.getMessage());
            for (Vacancy vacancy : batch) {
                vacancy.setEmbeddingRetries(vacancy.getEmbeddingRetries() + 1);
                vacancyRepository.save(vacancy);
            }
        }
    }

    public String storeEmbedding(Vacancy vacancy) {
        try {
            List<String> chunks = buildChunks(vacancy);
            String firstDocId = null;

            float[][] vectors = getEmbeddingBatch(
                    chunks.stream()
                            .map(t -> DOCUMENT_PREFIX + t)
                            .toList()
            );

            if (vectors == null) {
                log.warn("Null vector for vacancy {}", vacancy.getExternalId());
                return null;
            }

            for (int i = 0; i < vectors.length; i++) {
                String docId = UUID.randomUUID().toString();
                String metadata = String.format(
                        "{\"vacancyId\":\"%s\",\"siteName\":\"%s\",\"chunk\":%d}",
                        vacancy.getId(), vacancy.getSiteName(), i
                );

                jdbcTemplate.update(
                        "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?::uuid, ?, ?::jsonb, ?::vector)",
                        docId, chunks.get(i), metadata, vectorToString(vectors[i])
                );

                if (i == 0) firstDocId = docId;
            }

            log.debug("Embedding saved [{}] ({} chunks)", vacancy.getExternalId(), chunks.size());
            return firstDocId;

        } catch (Exception e) {
            log.error("Error saving embedding [{}]: {} {}", vacancy.getExternalId(), e.getClass().getName(), e.getMessage());
            return null;
        }
    }

    public void deleteEmbedding(String embeddingId) {
        if (embeddingId == null) return;
        try {
            jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'vacancyId' IN (SELECT metadata->>'vacancyId' FROM vector_store WHERE id = ?::uuid)",
                    embeddingId
            );
        } catch (Exception e) {
            log.error("Error deleting embedding {}: {}", embeddingId, e.getMessage());
        }
    }

    public String getQueryVector(String phrase) {
        float[] vector = getEmbeddingWithRateLimit(QUERY_PREFIX + phrase, null);
        if (vector == null) return null;
        return vectorToString(vector);
    }

    public String getQueryVectorWithRateLimit(String phrase, Long userId) {
        float[] vector = getEmbeddingWithRateLimit(QUERY_PREFIX + phrase, userId);
        if (vector == null) return null;
        return vectorToString(vector);
    }

    private float[] getEmbeddingWithRateLimit(String text, Long userId) {
        int delayIndex = 0;
        if (userId != null) {
            delayIndex = userDelayIndex.getOrDefault(userId, 0);
        }

        while (!ollamaBusy.compareAndSet(false, true)) {
            int delaySec = FIBONACCI_DELAYS[Math.min(delayIndex, FIBONACCI_DELAYS.length - 1)];
            log.info("Ollama busy, user {} waiting {} sec (fib step {})", userId, delaySec, delayIndex);
            try {
                Thread.sleep(delaySec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ollamaBusy.set(false);
                return null;
            }
            delayIndex++;
            if (userId != null) {
                userDelayIndex.put(userId, delayIndex);
            }
        }

        try {
            float[] result = getEmbedding(text);
            if (userId != null) {
                userDelayIndex.remove(userId);
            }
            return result;
        } finally {
            ollamaBusy.set(false);
        }
    }

    public List<String> findSimilarVacancyIdsByVector(String vectorStr, SearchMode mode, int topK) {
        try {
            String sql = """
                    SELECT metadata->>'vacancyId' as vacancy_id
                    FROM vector_store
                    WHERE 1 - (embedding <=> ?::vector) >= ?
                    GROUP BY metadata->>'vacancyId'
                    ORDER BY MAX(1 - (embedding <=> ?::vector)) DESC
                    LIMIT ?
                    """;
            List<String> result = jdbcTemplate.queryForList(sql, String.class,
                    vectorStr, mode.threshold, vectorStr, topK);
            log.info("Vector search by precomputed vector: mode={}, found={}", mode, result.size());
            return result;
        } catch (Exception e) {
            log.error("Vector search error: {} {}", e.getClass().getName(), e.getMessage());
            return List.of();
        }
    }

    public List<String> findSimilarVacancyIds(String query, SearchMode mode, int topK) {
        try {
            float[] queryVector = getEmbedding(QUERY_PREFIX + query);
            if (queryVector == null) {
                log.warn("Failed to get vector for query: {}", query);
                return List.of();
            }

            String sql = """
                    SELECT metadata->>'vacancyId' as vacancy_id
                    FROM vector_store
                    WHERE 1 - (embedding <=> ?::vector) >= ?
                    GROUP BY metadata->>'vacancyId'
                    ORDER BY MAX(1 - (embedding <=> ?::vector)) DESC
                    LIMIT ?
                    """;

            String vectorStr = vectorToString(queryVector);
            List<String> result = jdbcTemplate.queryForList(sql, String.class,
                    vectorStr, mode.threshold, vectorStr, topK);

            log.info("Vector search: query='{}', mode={}, found={}", query, mode, result.size());
            return result;

        } catch (Exception e) {
            log.error("Vector search error: {} {}", e.getClass().getName(), e.getMessage());
            return List.of();
        }
    }

    private float[][] getEmbeddingBatch(List<String> texts) {
        try {
            List<String> truncated = texts.stream()
                    .map(t -> t.length() > 1500 ? t.substring(0, 1500) : t)
                    .toList();

            String body = objectMapper.writeValueAsString(
                    Map.of("model", OLLAMA_MODEL, "input", truncated)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode embeddings = objectMapper.readTree(response.body()).path("embeddings");

            if (!embeddings.isArray() || embeddings.isEmpty()) {
                log.error("Ollama returned empty embeddings array. Response: {}",
                        response.body().substring(0, Math.min(200, response.body().length())));
                return null;
            }

            float[][] result = new float[embeddings.size()][];
            for (int i = 0; i < embeddings.size(); i++) {
                JsonNode node = embeddings.get(i);
                result[i] = new float[node.size()];
                for (int j = 0; j < node.size(); j++) {
                    result[i][j] = (float) node.get(j).asDouble();
                }
            }
            return result;

        } catch (Exception e) {
            log.error("Batch embedding error: {} {}", e.getClass().getName(), e.getMessage());
            return null;
        }
    }

    private float[] getEmbedding(String text) {
        float[][] batch = getEmbeddingBatch(List.of(text));
        if (batch == null || batch.length == 0) return null;
        return batch[0];
    }

    private String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private List<String> buildChunks(Vacancy vacancy) {
        List<String> chunks = new ArrayList<>();

        String header = String.join(" | ",
                notEmpty(vacancy.getTitle()),
                notEmpty(vacancy.getCity()),
                notEmpty(vacancy.getExperience()),
                notEmpty(vacancy.getEmployment())
        );
        chunks.add(header);

        if (notBlank(vacancy.getDescription())) {
            chunks.add(vacancy.getTitle() + "\n" + vacancy.getDescription());
        }
        if (notBlank(vacancy.getDuties())) {
            chunks.add(vacancy.getTitle() + "\nОбязанности:\n" + vacancy.getDuties());
        }
        if (notBlank(vacancy.getRequirements())) {
            chunks.add(vacancy.getTitle() + "\nТребования:\n" + vacancy.getRequirements());
        }
        if (notBlank(vacancy.getConditions())) {
            chunks.add(vacancy.getTitle() + "\nУсловия:\n" + vacancy.getConditions());
        }

        return chunks;
    }

    private String notEmpty(String s) {
        return s != null && !s.isBlank() ? s : "";
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}