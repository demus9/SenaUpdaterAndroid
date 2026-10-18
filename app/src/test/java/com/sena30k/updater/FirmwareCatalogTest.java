package com.sena30k.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FirmwareCatalogTest {
    private static final String INDEX = "SENA\n"
            + "33iV2:v4.1:33i-v4.1-build0.img\n"
            + "5SV2:1\n"
            + "30KV2:v4.5:English:30K-v4.5.1-build0.img\n"
            + "30KV2:v4.5:French:30K-v4.5.1-build0.img\n"
            + "20S:v2.2:English:20S-v2.2.3-build1.img\n"
            + "20S:v2.2:French:20S-v2.2.3-build1-fr.img\r\n"
            + "BAD:v1.0:../evil.img\n"
            + "BAD2:v1.0:Dutch:x/y.img\n"
            + "\n";

    private static FirmwareCatalog catalog() {
        FirmwareCatalog catalog = FirmwareCatalog.parseIndex(INDEX);
        catalog.addOlderCsv("Model,Version,File,Url,Bytes,ScannedAt\r\n"
                + "30KV2,4.0,30K-v4.0.img,u,1,t\n"
                + "30KV2,4.4.1,30K-v4.4.1-build1.img,u,1,t\n"
                + "30KV2,4.5,30K-v4.5.1-build0.img,u,1,t\n"
                + "30KV2,9.9,../x.img,u,1,t\n"
                + "UNKNOWN,1.0,U-v1.0.img,u,1,t\n");
        return catalog;
    }

    @Test
    public void ignoresUnsafeAndMalformedLines() {
        assertEquals(Arrays.asList("20S", "30KV2", "33iV2"), catalog().models());
    }

    @Test
    public void identicalFilesAcrossLanguagesAreUniversal() {
        assertEquals(List.of(FirmwareCatalog.UNIVERSAL), catalog().languages("30KV2"));
        assertEquals(List.of(FirmwareCatalog.UNIVERSAL), catalog().languages("33iV2"));
    }

    @Test
    public void differingFilesOfferLanguages() {
        assertEquals(Arrays.asList("English", "French"), catalog().languages("20S"));
        assertEquals("20S-v2.2.3-build1-fr.img",
                catalog().versions("20S", "French", true).get(0).fileName);
    }

    @Test
    public void versionComesFromFileNameNotIndex() {
        FirmwareCatalog.Entry latest =
                catalog().versions("30KV2", FirmwareCatalog.UNIVERSAL, true).get(0);
        assertEquals("v4.5.1", latest.versionText());
        assertEquals("v4.5.1 (build 0) – current", latest.label());
        assertFalse(latest.older);
    }

    @Test
    public void listsOlderVersionsNewestFirstWithoutDuplicates() {
        List<FirmwareCatalog.Entry> all =
                catalog().versions("30KV2", FirmwareCatalog.UNIVERSAL, false);
        assertEquals(3, all.size());
        assertEquals("30K-v4.5.1-build0.img", all.get(0).fileName);
        assertEquals("30K-v4.4.1-build1.img", all.get(1).fileName);
        assertEquals("30K-v4.0.img", all.get(2).fileName);
        assertTrue(all.get(2).older);
        assertEquals(1, catalog().versions("30KV2", FirmwareCatalog.UNIVERSAL, true).size());
    }

    @Test
    public void validatesDownloadFileNames() {
        assertTrue(FirmwareCatalog.isSafeFileName("30K-v4.5.1-build0.img"));
        assertFalse(FirmwareCatalog.isSafeFileName("../a.img"));
        assertFalse(FirmwareCatalog.isSafeFileName("a.img?x=1"));
        assertFalse(FirmwareCatalog.isSafeFileName("a.bin"));
    }
}
