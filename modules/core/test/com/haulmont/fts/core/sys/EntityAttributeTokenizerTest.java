/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */
package com.haulmont.fts.core.sys;

import junit.framework.TestCase;

import java.io.StringReader;

public class EntityAttributeTokenizerTest extends TestCase {

    public void test() {
        EntityAttributeTokenizer tokenizer = new EntityAttributeTokenizer(new StringReader("abcd"));

        boolean b = tokenizer.isTokenChar('^');
        assertTrue(b);

        b = tokenizer.isTokenChar('"');
        assertFalse(b);

        b = tokenizer.isTokenChar('$');
        assertTrue(b);
    }
}
