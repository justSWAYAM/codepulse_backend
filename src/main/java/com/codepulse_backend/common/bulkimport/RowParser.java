package com.codepulse_backend.common.bulkimport;

@FunctionalInterface
public interface RowParser<R, T> {
    T parse(R raw) throws RowRejectedException;
}
