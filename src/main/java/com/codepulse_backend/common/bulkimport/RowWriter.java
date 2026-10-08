package com.codepulse_backend.common.bulkimport;

@FunctionalInterface
public interface RowWriter<T> {
    void write(T parsed);
}
