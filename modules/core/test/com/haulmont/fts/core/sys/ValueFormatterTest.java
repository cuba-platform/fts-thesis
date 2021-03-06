/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */
package com.haulmont.fts.core.sys;

import com.haulmont.fts.global.ValueFormatter;
import junit.framework.TestCase;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ValueFormatterTest extends TestCase {

    public void testFormat() throws Exception {
        ValueFormatter valueFormatter = new ValueFormatter();

        BigDecimal bigDecimal = new BigDecimal("12345.67");
        String str = valueFormatter.format(bigDecimal);
        assertEquals("12345_67", str);

        Double dbl = 12345.67;
        str = valueFormatter.format(dbl);
        assertEquals("12345_67", str);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = dateFormat.parse("2010-07-26 17:58:11");
        str = valueFormatter.format(date);
        assertEquals("2010-07-26", str);
    }
}
