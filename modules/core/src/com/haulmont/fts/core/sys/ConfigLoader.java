/*
 * Copyright (c) 2010 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Konstantin Krivopustov
 * Created: 24.06.2010 19:19:00
 *
 * $Id$
 */
package com.haulmont.fts.core.sys;

import com.haulmont.bali.util.Dom4j;
import com.haulmont.bali.util.ReflectionHelper;
import com.haulmont.chile.core.datatypes.Datatype;
import com.haulmont.chile.core.datatypes.Datatypes;
import com.haulmont.chile.core.datatypes.impl.*;
import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.chile.core.model.MetaProperty;
import com.haulmont.cuba.core.global.ConfigProvider;
import com.haulmont.cuba.core.global.GlobalConfig;
import com.haulmont.cuba.core.global.MetadataProvider;
import com.haulmont.cuba.core.sys.AppContext;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrTokenizer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Document;
import org.dom4j.Element;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {

    private static Log log = LogFactory.getLog(ConfigLoader.class);
    
    private static final String DEFAULT_CONFIG = "/cuba-fts.xml";

    private static String[] systemProps = new String[] {
            "id", "createTs", "createdBy", "version", "updateTs", "updatedBy", "deleteTs", "deletedBy"
    };
    protected String confDir;

    static {
        Arrays.sort(systemProps);
    }

    public ConfigLoader() {
        confDir = ConfigProvider.getConfig(GlobalConfig.class).getConfDir();
    }

    public Map<String, EntityDescr> loadConfiguration() {
        HashMap<String, EntityDescr> map = new HashMap<String, EntityDescr>();

        String configName = AppContext.getProperty("cuba.ftsConfig");
        if (StringUtils.isBlank(configName))
            configName = DEFAULT_CONFIG;

        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        StrTokenizer tokenizer = new StrTokenizer(configName);
        for (String location : tokenizer.getTokenArray()) {
            Resource resource = resourceLoader.getResource(location);
            if (resource.exists()) {
                InputStream stream = null;
                try {
                    stream = resource.getInputStream();
                    loadFromStream(stream, map);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    IOUtils.closeQuietly(stream);
                }
            } else {
                log.warn("Resource " + location + " not found, ignore it");
            }
        }

        return map;
    }

    private void loadFromStream(InputStream stream, Map<String, EntityDescr> map) {
        Document document = Dom4j.readDocument(stream);
        for (Element element : Dom4j.elements(document.getRootElement(), "include")) {
            String fileName = element.attributeValue("file");
            if (!StringUtils.isBlank(fileName)) {
                InputStream incStream = getClass().getResourceAsStream(fileName);
                loadFromStream(incStream, map);
            }
        }

        Element rootElem = document.getRootElement();
        Element entitiesElem = rootElem.element("entities");
        for (Element entityElem : Dom4j.elements(entitiesElem, "entity")) {
            String className = entityElem.attributeValue("class");
            MetaClass metaClass = MetadataProvider.getSession().getClass(ReflectionHelper.getClass(className));

            Element searchableIfScriptElem = entityElem.element("searchableIf");
            String searchableIfScript = searchableIfScriptElem != null ? searchableIfScriptElem.getText() : null;

            Element searchablesScriptElem = entityElem.element("searchables");
            String searchablesScript = searchablesScriptElem != null ? searchablesScriptElem.getText() : null;

            String showStr = entityElem.attributeValue("show");
            boolean show = showStr == null || Boolean.valueOf(showStr);

            EntityDescr entityDescr = new EntityDescr(metaClass, searchableIfScript, searchablesScript, show);

            for (Element element : Dom4j.elements(entityElem, "include")) {
                String re = element.attributeValue("re");
                if (!StringUtils.isBlank(re))
                    includeByRe(entityDescr, metaClass, re);
                else {
                    String name = element.attributeValue("name");
                    if (!StringUtils.isBlank(name))
                        includeByName(entityDescr, metaClass, name);
                }
            }

            for (Element element : Dom4j.elements(entityElem, "exclude")) {
                String re = element.attributeValue("re");
                if (!StringUtils.isBlank(re))
                    excludeByRe(entityDescr, metaClass, re);
                else {
                    String name = element.attributeValue("name");
                    if (!StringUtils.isBlank(name))
                        excludeByName(entityDescr, metaClass, name);
                }
            }

            map.put(className, entityDescr);
        }
    }

    private void includeByName(EntityDescr descr, MetaClass metaClass, String name) {
        if (metaClass.getPropertyEx(name) != null)
            descr.addProperty(name);
    }

    private void includeByRe(EntityDescr descr, MetaClass metaClass, String re) {
        Pattern pattern = Pattern.compile(re);
        for (MetaProperty metaProperty : metaClass.getProperties()) {
            if (isSearchableProperty(metaProperty)) {
                Matcher matcher = pattern.matcher(metaProperty.getName());
                if (matcher.matches())
                    descr.addProperty(metaProperty.getName());
            }
        }
    }

    private void excludeByName(EntityDescr descr, MetaClass metaClass, String name) {
        descr.removeProperty(name);
    }

    private void excludeByRe(EntityDescr descr, MetaClass metaClass, String re) {
        Pattern pattern = Pattern.compile(re);
        for (String property : descr.getPropertyNames()) {
            Matcher matcher = pattern.matcher(property);
            if (matcher.matches())
                descr.removeProperty(property);
        }
    }

    private boolean isSearchableProperty(MetaProperty metaProperty) {
        if (Arrays.binarySearch(systemProps, metaProperty.getName()) >= 0)
            return false;

        if (metaProperty.getRange().isDatatype()) {
            Datatype dt = metaProperty.getRange().asDatatype();
            return (Datatypes.getInstance().get(StringDatatype.NAME).equals(dt)
                    || Datatypes.getInstance().get(DateDatatype.NAME).equals(dt)
                    || Datatypes.getInstance().get(BigDecimalDatatype.NAME).equals(dt)
                    || Datatypes.getInstance().get(IntegerDatatype.NAME).equals(dt)
                    || Datatypes.getInstance().get(DoubleDatatype.NAME).equals(dt));

        } else if (metaProperty.getRange().isEnum() || metaProperty.getRange().isClass()) {
            return true;
        }

        return false;
    }
}
