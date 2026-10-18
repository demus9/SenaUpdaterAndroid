package com.sena30k.updater;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Firmware list built from Sena's plain-text index ("Model:Version:Language:File" or
 * "Model:Version:File" per line) plus an optional CSV of older files found by the scan script.
 */
public final class FirmwareCatalog {
    public static final String UNIVERSAL = "Universal";
    public static final String BASE_URL = "https://firmware.sena.com/senabluetoothmanager/";

    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}\\.img");
    private static final Pattern FILE_VERSION = Pattern.compile("[-_][vV](\\d+(?:\\.\\d+)*)");
    private static final Pattern FILE_BUILD = Pattern.compile("build(\\d+)");

    private static final Comparator<Entry> NEWEST_FIRST = (left, right) -> {
        int byVersion = compareNumbers(right.versionParts(), left.versionParts());
        return byVersion != 0 ? byVersion : Integer.compare(right.build(), left.build());
    };

    private final Map<String, List<Entry>> byModel = new LinkedHashMap<>();

    public static boolean isSafeFileName(String fileName) {
        return fileName != null && SAFE_FILE_NAME.matcher(fileName).matches();
    }

    public static FirmwareCatalog parseIndex(String indexText) {
        FirmwareCatalog catalog = new FirmwareCatalog();
        for (String rawLine : indexText.split("\n")) {
            String[] parts = rawLine.trim().split(":");
            String language = null;
            String file;
            if (parts.length == 3) {
                file = parts[2];
            } else if (parts.length == 4) {
                language = parts[2];
                file = parts[3];
            } else {
                continue;
            }
            // File names become URL path segments, so anything unusual is dropped.
            if (parts[0].isEmpty() || !isSafeFileName(file)) {
                continue;
            }
            catalog.add(new Entry(parts[0], language, file, parts[1], false));
        }
        return catalog;
    }

    /**
     * Adds older files from the scan CSV (Model,Version,File,Url,Bytes,ScannedAt). The scan
     * derives names from the model's first listed file, so language-specific models get these
     * entries under that first language only.
     */
    public void addOlderCsv(String csvText) {
        for (String rawLine : csvText.split("\n")) {
            String[] parts = rawLine.trim().split(",");
            if (parts.length < 3 || parts[0].equals("Model")) {
                continue;
            }
            String model = parts[0].trim();
            String file = parts[2].trim();
            List<Entry> entries = byModel.get(model);
            if (entries == null || !isSafeFileName(file) || hasFile(entries, file)) {
                continue;
            }
            add(new Entry(model, entries.get(0).language, file, parts[1].trim(), true));
        }
    }

    private void add(Entry entry) {
        byModel.computeIfAbsent(entry.model, key -> new ArrayList<>()).add(entry);
    }

    private static boolean hasFile(List<Entry> entries, String file) {
        for (Entry entry : entries) {
            if (entry.fileName.equals(file)) {
                return true;
            }
        }
        return false;
    }

    public List<String> models() {
        List<String> models = new ArrayList<>(byModel.keySet());
        Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    /** "Universal" alone when every current file is identical or no language is given. */
    public List<String> languages(String model) {
        List<Entry> entries = byModel.get(model);
        if (entries == null) {
            return Collections.emptyList();
        }
        TreeSet<String> files = new TreeSet<>();
        List<String> languages = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.older) {
                continue;
            }
            files.add(entry.fileName);
            if (entry.language != null && !languages.contains(entry.language)) {
                languages.add(entry.language);
            }
        }
        if (files.size() <= 1 || languages.isEmpty()) {
            return Collections.singletonList(UNIVERSAL);
        }
        return languages;
    }

    /** Entries for a model and language, newest first; only the newest when requested. */
    public List<Entry> versions(String model, String language, boolean onlyLatest) {
        List<Entry> entries = byModel.get(model);
        if (entries == null) {
            return Collections.emptyList();
        }
        boolean universal = UNIVERSAL.equals(language) && languages(model).size() == 1
                && UNIVERSAL.equals(languages(model).get(0));
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            boolean matches = universal || (language != null && language.equals(entry.language));
            if (matches && !hasFile(result, entry.fileName)) {
                result.add(entry);
            }
        }
        Collections.sort(result, NEWEST_FIRST);
        if (onlyLatest && result.size() > 1) {
            return new ArrayList<>(result.subList(0, 1));
        }
        return result;
    }

    private static int compareNumbers(int[] left, int[] right) {
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    public static final class Entry {
        public final String model;
        public final String language;
        public final String fileName;
        public final String listedVersion;
        public final boolean older;

        Entry(String model, String language, String fileName, String listedVersion,
                boolean older) {
            this.model = model;
            this.language = language;
            this.fileName = fileName;
            this.listedVersion = listedVersion;
            this.older = older;
        }

        /** The index may list "v4.5" for a file that is really 4.5.1, so prefer the file name. */
        public String versionText() {
            Matcher matcher = FILE_VERSION.matcher(fileName);
            return matcher.find() ? "v" + matcher.group(1) : listedVersion;
        }

        int[] versionParts() {
            String[] pieces = versionText().replaceFirst("^[vV]", "").split("\\.");
            int[] numbers = new int[pieces.length];
            for (int i = 0; i < pieces.length; i++) {
                String digits = pieces[i].replaceAll("\\D", "");
                numbers[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            }
            return numbers;
        }

        int build() {
            Matcher matcher = FILE_BUILD.matcher(fileName);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }

        public String label() {
            int build = build();
            return versionText() + (build >= 0 ? " (build " + build + ")" : "")
                    + (older ? " – older" : " – current");
        }

        public String url() {
            return BASE_URL + fileName;
        }
    }
}
