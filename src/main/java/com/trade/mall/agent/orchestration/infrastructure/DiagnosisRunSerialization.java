package com.trade.mall.agent.orchestration.infrastructure;

import com.trade.mall.agent.orchestration.DiagnosisRun;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/** 内部快照编码器：仅供 DiagnosisRunStore 实现使用，不暴露给领域层。 */
final class DiagnosisRunSerialization {

    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
        "maxdepth=32;maxrefs=20000;maxbytes=16777216;com.trade.mall.agent.**;java.base/*;java.math.*;!*"
    );

    private DiagnosisRunSerialization() {}

    static byte[] encode(DiagnosisRun run) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(run);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize diagnosis checkpoint: " + run.diagnosisId(), e);
        }
    }

    static DiagnosisRun decode(byte[] bytes) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(FILTER);
            Object value = in.readObject();
            if (!(value instanceof DiagnosisRun run)) {
                throw new IllegalStateException("checkpoint payload is not DiagnosisRun: " + value.getClass().getName());
            }
            return run;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("cannot deserialize diagnosis checkpoint", e);
        }
    }
}

