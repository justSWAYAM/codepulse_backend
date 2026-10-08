package com.codepulse_backend.common.bulkimport;

import java.util.List;

public record BulkRun<T>(List<RowOutcome<T>> rows) {
    public int total() {
        return rows.size();
    }

    public long validCount() {
        return rows.stream().filter(RowOutcome::valid).count();
    }

    public long invalidCount() {
        return rows.size() - validCount();
    }

    public long writtenCount() {
        return rows.stream().filter(RowOutcome::written).count();
    }
}
