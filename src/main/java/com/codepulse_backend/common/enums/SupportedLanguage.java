package com.codepulse_backend.common.enums;

public enum SupportedLanguage {

    JAVA("Java", 62),
    PYTHON("Python", 71),
    CPP("C++", 54),
    C("C", 50),
    JAVASCRIPT("JavaScript", 63);

    private final String displayName;
    private final int judge0LanguageId;

    SupportedLanguage(String displayName, int judge0LanguageId) {
        this.displayName = displayName;
        this.judge0LanguageId = judge0LanguageId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getJudge0LanguageId() {
        return judge0LanguageId;
    }
    public static SupportedLanguage fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Language name cannot be empty");
        }
        for (SupportedLanguage language : values()) {
            if (language.name().equalsIgnoreCase(name) || language.getDisplayName().equalsIgnoreCase(name)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unsupported language: " + name);
    }
}