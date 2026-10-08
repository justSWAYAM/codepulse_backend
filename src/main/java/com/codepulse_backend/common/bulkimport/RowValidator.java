package com.codepulse_backend.common.bulkimport;

import java.util.List;

@FunctionalInterface
public interface RowValidator<T> {
    List<String> validate(T parsed);     // empty list = valid
}
