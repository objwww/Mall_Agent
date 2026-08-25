package com.trade.mall.agent.orchestration.infrastructure;

import com.trade.mall.agent.orchestration.DiagnosisRun;
import com.trade.mall.agent.orchestration.DiagnosisRunStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 单机耐久版 DiagnosisRunStore（诊断运行存储）。写入采用“临时文件 + 原子 rename（重命名）”，
 * 可以证明 Java 进程重启后检查点仍能恢复；多实例生产部署应使用 {@link JdbcDiagnosisRunStore}。
 */
public final class FileDiagnosisRunStore implements DiagnosisRunStore {

    private final Path directory;

    public FileDiagnosisRunStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create diagnosis checkpoint directory: " + directory, e);
        }
    }

    @Override
    public synchronized void save(DiagnosisRun run) {
        Path target = pathOf(run.diagnosisId());
        Optional<DiagnosisRun> current = find(run.diagnosisId());
        if (current.isPresent() && current.get().seq() > run.seq()) {
            throw new IllegalStateException(
                "refuse stale diagnosis checkpoint: diagnosisId=" + run.diagnosisId()
                    + " storedSeq=" + current.get().seq() + " incomingSeq=" + run.seq());
        }

        byte[] payload = DiagnosisRunSerialization.encode(run);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().getId());
        try {
            Files.write(temp, payload);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            throw new IllegalStateException("cannot persist diagnosis checkpoint: " + run.diagnosisId(), e);
        }
    }

    @Override
    public synchronized Optional<DiagnosisRun> find(String diagnosisId) {
        Path path = pathOf(diagnosisId);
        if (!Files.exists(path)) return Optional.empty();
        try {
            DiagnosisRun run = DiagnosisRunSerialization.decode(Files.readAllBytes(path));
            if (!diagnosisId.equals(run.diagnosisId())) {
                throw new IllegalStateException("checkpoint diagnosisId mismatch: expected=" + diagnosisId
                    + " actual=" + run.diagnosisId());
            }
            return Optional.of(run);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read diagnosis checkpoint: " + diagnosisId, e);
        }
    }

    @Override
    public synchronized List<DiagnosisRun> findByState(com.trade.mall.agent.orchestration.DiagnosisState state, int limit) {
        if (limit <= 0) return List.of();
        try (var paths = Files.list(directory)) {
            List<DiagnosisRun> matches = new ArrayList<>();
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".checkpoint")).toList()) {
                DiagnosisRun run = DiagnosisRunSerialization.decode(Files.readAllBytes(path));
                if (run.state() == state) matches.add(run);
            }
            matches.sort(Comparator.comparingInt(DiagnosisRun::seq).thenComparing(DiagnosisRun::diagnosisId));
            return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan diagnosis checkpoints: " + directory, e);
        }
    }

    @Override
    public synchronized List<DiagnosisRun> recentTerminal(int limit) {
        if (limit <= 0) return List.of();
        try (var paths = Files.list(directory)) {
            List<DiagnosisRun> matches = new ArrayList<>();
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".checkpoint")).toList()) {
                DiagnosisRun run = DiagnosisRunSerialization.decode(Files.readAllBytes(path));
                if (run.isTerminal()) matches.add(run);
            }
            matches.sort(Comparator.comparingInt(DiagnosisRun::seq).reversed().thenComparing(DiagnosisRun::diagnosisId));
            return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan terminal diagnosis checkpoints: " + directory, e);
        }
    }

    private Path pathOf(String diagnosisId) {
        if (diagnosisId == null || diagnosisId.isBlank()) {
            throw new IllegalArgumentException("diagnosisId must not be blank");
        }
        return directory.resolve(sha256(diagnosisId) + ".checkpoint");
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

