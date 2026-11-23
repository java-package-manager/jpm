package dev.jpm.license;

import dev.jpm.api.json.Jsonc;
import dev.jpm.testing.Checkout;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicensesTest {
    @Test
    void readsExpressions() {
        assertEquals(List.of("MIT", "Apache-2.0"), Licenses.ids("MIT OR Apache-2.0"));
        assertEquals(List.of("GPL-2.0-only"), Licenses.ids("GPL-2.0-only WITH Classpath-exception-2.0"), "an exception is not a license");
        assertEquals(List.of("MIT"), Licenses.ids("(MIT AND LicenseRef-mine)"));
        assertEquals(List.of(), Licenses.unknown("MIT OR Apache-2.0"));
        assertEquals(List.of("Apach-2.0"), Licenses.unknown("MIT OR Apach-2.0"));
        assertEquals("Apache-2.0", Licenses.find("apache-2.0").id(), "SPDX ids match ignoring case");
    }

    @Test
    void suggestsTheIdMeant() {
        assertEquals("Apache-2.0", Licenses.suggest("Apach-2.0"));
        assertEquals("GPL-3.0-only", Licenses.suggest("GPL-3.0"));
        assertEquals("MIT", Licenses.suggest("MTI"));
        assertNull(Licenses.suggest("completely-made-up-license"));
    }

    @Test
    void findsTheIdOfAPomLicense() {
        assertEquals("Apache-2.0", Licenses.forNameOrUrl("The Apache Software License, Version 2.0", null));
        assertEquals("Apache-2.0", Licenses.forNameOrUrl("Apache License, Version 2.0", null));
        assertEquals("MIT", Licenses.forNameOrUrl("MIT License", null));
        assertEquals("MIT", Licenses.forNameOrUrl("MIT", null));
        assertEquals("Apache-2.0", Licenses.forNameOrUrl("ASL", "https://www.apache.org/licenses/LICENSE-2.0.txt"));
        assertNull(Licenses.forNameOrUrl("Our own license", "https://example.com/license"));
    }

    @Test
    void recognisesLicenseTexts() {
        assertEquals(List.of("MIT"), Licenses.detect("MIT License\n\nCopyright (c) 2026 Ann\n\nPermission is hereby granted, free of charge, to any person"
                + " ...\n\nThe above copyright notice and this permission\n notice shall be included in all copies."));
        assertEquals("Apache-2.0", Licenses.detect("                                 Apache License\n                           Version 2.0, January 2004").get(0));
        assertEquals("GPL-3.0-or-later", Licenses.detect("GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007").get(0));
        assertEquals("LGPL-2.1-or-later", Licenses.detect("GNU LESSER GENERAL PUBLIC LICENSE\nVersion 2.1, February 1999").get(0));
        assertEquals("BSD-2-Clause", Licenses.detect("Redistribution and use in source and binary forms, with or without modification").get(0));
        assertEquals("BSD-3-Clause", Licenses.detect("Redistribution and use in source and binary forms ... 3. Neither the name of").get(0));
        assertEquals(List.of(), Licenses.detect("All rights reserved."));
        assertTrue(Licenses.names("GPL-3.0-only", Licenses.detect("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007")));
        assertTrue(Licenses.names("MIT OR Apache-2.0", List.of("MIT")));
        assertFalse(Licenses.names("Apache-2.0", List.of("MIT")));
    }

    @Test
    void fillsInTheYearAndTheHolder() {
        assertEquals("Copyright (c) 2026 Ann Smith\n", Licenses.fill("Copyright (c) <year> <copyright holders>", 2026, "Ann Smith"));
        assertEquals("Copyright 2026 Ann\n", Licenses.fill("Copyright [yyyy] [name of copyright owner]", 2026, "Ann"));
        assertEquals("Copyright (c) 2026 <copyright holders>\n", Licenses.fill("Copyright (c) <year> <copyright holders>", 2026, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaListsEveryCurrentId() {
        Map<String, Object> schema = (Map<String, Object>) Jsonc.parse(Fs.read(Checkout.root().resolve("cli/src/assets/dev/jpm/project.schema.json")));
        Map<String, Object> license = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("license");
        List<Object> enumIds = (List<Object>) ((Map<String, Object>) ((List<Object>) license.get("anyOf")).get(0)).get("enum");
        List<String> current = Licenses.all().values().stream().filter(l -> !l.deprecated()).map(Licenses.License::id).toList();
        assertEquals(current, enumIds, "run python3 schema/spdx.py to bring the schema and spdx-licenses.json back in step");
        assertTrue(Licenses.listVersion().matches("\\d+\\.\\d+(\\.\\d+)?"));
    }
}
