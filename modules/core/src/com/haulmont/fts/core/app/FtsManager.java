/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */
package com.haulmont.fts.core.app;

import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.cuba.core.EntityManager;
import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.Query;
import com.haulmont.cuba.core.Transaction;
import com.haulmont.cuba.core.app.FtsSender;
import com.haulmont.cuba.core.app.ServerInfoAPI;
import com.haulmont.cuba.core.entity.BaseEntity;
import com.haulmont.cuba.core.entity.FtsChangeType;
import com.haulmont.cuba.core.entity.FtsQueue;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.persistence.DbTypeConverter;
import com.haulmont.cuba.security.app.Authenticated;
import com.haulmont.cuba.security.app.Authentication;
import com.haulmont.fts.core.sys.*;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.index.IndexUpgrader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import javax.annotation.ManagedBean;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author krivopustov
 * @version $Id$
 */
@ManagedBean(FtsManagerAPI.NAME)
public class FtsManager implements FtsManagerAPI {

    private Log log = LogFactory.getLog(FtsManager.class);

    private volatile Map<String, EntityDescr> descrByClassName;
    private volatile Map<String, EntityDescr> descrByName;

    protected final ReentrantLock writeLock = new ReentrantLock();
    protected volatile boolean writing;

    protected volatile Directory directory;

    protected static final int DEL_CHUNK = 10;

    protected FtsConfig config;

    protected String serverId;

    @Inject
    protected Authentication authentication;

    @Inject
    protected Persistence persistence;

    @Inject
    protected Scripting scripting;

    @Inject
    protected Metadata metadata;

    @Inject
    protected ConfigLoader configLoader;

    @Inject
    public void setConfiguration(Configuration configuration) {
        config = configuration.getConfig(FtsConfig.class);
    }

    @Inject
    public void setServerInfo(ServerInfoAPI serverInfo) {
        serverId = serverInfo.getServerId();
    }

    @Override
    public boolean isEnabled() {
        return config.getEnabled();
    }

    @Authenticated
    @Override
    public void setEnabled(boolean value) {
        config.setEnabled(value);
    }

    @Override
    public boolean isWriting() {
        return writing;
    }

    protected Map<String, EntityDescr> getDescrByClassName() {
        if (descrByClassName == null) {
            synchronized (this) {
                if (descrByClassName == null) {
                    descrByClassName = configLoader.loadConfiguration();
                }
            }
        }
        return descrByClassName;
    }

    public Map<String, EntityDescr> getDescrByName() {
        if (descrByName == null) {
            synchronized (this) {
                if (descrByName == null) {
                    descrByName = new HashMap<>(getDescrByClassName().size());
                    for (EntityDescr descr : getDescrByClassName().values()) {
                        String name = descr.getMetaClass().getName();
                        descrByName.put(name, descr);
                    }
                }
            }
        }
        return descrByName;
    }

    public List<BaseEntity> getSearchableEntities(BaseEntity entity) {
        List<BaseEntity> list = new ArrayList<>();

        EntityDescr descr = getDescrByClassName().get(entity.getClass().getName());
        if (descr == null) {
            Class originalClass = metadata.getExtendedEntities().getOriginalClass(entity.getMetaClass());
            if (originalClass != null)
                descr = getDescrByClassName().get(originalClass.getName());
            if (descr == null)
                return list;
        }

        Set<String> properties = descr.getPropertyNames();

        Set<String> ownProperties = new HashSet<>(properties.size());
        for (String property : properties) {
            String p = property.indexOf(".") < 0 ? property : property.substring(0, property.indexOf("."));
            ownProperties.add(p);
        }

        Set<String> dirty = persistence.getTools().getDirtyFields(entity);
        for (String s : dirty) {
            if (ownProperties.contains(s)) {
                if (StringUtils.isBlank(descr.getSearchableIfScript())) {
                    list.add(entity);
                } else if (runSearchableIf(entity, descr)) {
                    list.add(entity);
                }
                break;
            }
        }

        if (!StringUtils.isBlank(descr.getSearchablesScript())) {
            Map<String, Object> params = new HashMap<>();
            params.put("entity", entity);
            params.put("searchables", list);
            scripting.evaluateGroovy(descr.getSearchablesScript(), params);
        }

        return list;
    }

    protected boolean runSearchableIf(BaseEntity entity, EntityDescr descr) {
        Map<String, Object> params = new HashMap<>();
        params.put("entity", entity);
        Boolean value = scripting.evaluateGroovy(descr.getSearchableIfScript(), params);
        return BooleanUtils.isTrue(value);
    }

    public int processQueue() {
        if (!AppContext.isStarted())
            return 0;

        if (!config.getEnabled())
            return 0;

        log.debug("Start processing queue");
        int count = 0;
        boolean locked = writeLock.tryLock();
        if (!locked) {
            log.warn("Unable to process queue: writing at the moment");
            return count;
        }

        authentication.begin();
        try {
            writing = true;

            List<FtsQueue> list = loadQueuedItems();
            list = new ArrayList<>(list);
            if (!list.isEmpty()) {
                count = initIndexer(count, list);
                removeQueuedItems(list);
            }
        } finally {
            writeLock.unlock();
            writing = false;
            authentication.end();
        }
        log.debug(count + " queue items succesfully processed");
        return count;
    }

    protected List<FtsQueue> loadQueuedItems() {
        List<FtsQueue> list;

        boolean useServerId = !config.getIndexingHosts().isEmpty();
        int maxSize = config.getIndexingBatchSize();

        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            String queryString = String.format("select q from sys$FtsQueue q where %s order by q.createTs",
                    (useServerId ? "q.indexingHost = ?1" : "q.indexingHost is null"));
            Query query = em.createQuery(queryString);
            if (useServerId)
                query.setParameter(1, serverId);
            query.setMaxResults(maxSize);
            list = query.getResultList();
            tx.commit();
        } finally {
            tx.end();
        }
        return list;
    }

    protected void removeQueuedItems(List<FtsQueue> list) {
        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();

            for (int i = 0; i < list.size(); i += DEL_CHUNK) {
                StringBuilder sb = new StringBuilder("delete from SYS_FTS_QUEUE where ID in (");
                List<FtsQueue> sublist = list.subList(i, Math.min(i + DEL_CHUNK, list.size()));
                for (int idx = 0; idx < sublist.size(); idx++) {
                    sb.append("?");
                    if (idx < sublist.size() - 1)
                        sb.append(", ");
                }
                sb.append(")");

                DbTypeConverter converter = persistence.getDbTypeConverter();

                Query query = em.createNativeQuery(sb.toString());
                for (int idx = 0; idx < sublist.size(); idx++) {
                    try {
                        query.setParameter(idx + 1, converter.getSqlObject(sublist.get(idx).getId()));
                    } catch (SQLException e) {
                        throw new RuntimeException("Unable to set query parameter", e);
                    }
                }
                query.executeUpdate();
            }

            tx.commit();
        } finally {
            tx.end();
        }
    }

    protected int initIndexer(int count, List<FtsQueue> list) {
        LuceneIndexer indexer = createLuceneIndexer();
        List<FtsQueue> unindexed = new ArrayList<>(list.size());
        try {
            for (FtsQueue ftsQueue : list) {
                try {
                    indexer.indexEntity(ftsQueue.getEntityName(), ftsQueue.getEntityId(), ftsQueue.getChangeType());
                    count++;
                } catch (EntityIndexingException e) {
                    unindexed.add(ftsQueue);
                }
            }
            if (!unindexed.isEmpty()) {
                list.removeAll(unindexed);
            }
        } finally {
            indexer.close();
        }
        return count;
    }

    protected LuceneIndexer createLuceneIndexer() {
        return new LuceneIndexer(getDescrByName(), getDirectory(), config.getStoreContentInIndex());
    }

    protected FtsConfig getConfig() {
        return config;
    }

    public String optimize() {
        if (!AppContext.isStarted())
            return "Application is not started";

        if (!config.getEnabled())
            return "FTS is disabled";

        log.debug("Start optimize");
        boolean locked = writeLock.tryLock();
        if (!locked) {
            return "Unable to optimize: writing at the moment";
        }

        authentication.begin();
        LuceneWriter luceneWriter = new LuceneWriter(getDirectory());
        try {
            writing = true;
            luceneWriter.optimize();
            return "Done";
        } catch (Throwable e) {
            log.error("Error", e);
            return ExceptionUtils.getStackTrace(e);
        } finally {
            luceneWriter.close();
            writeLock.unlock();
            writing = false;
            authentication.end();
        }
    }

    @Override
    public String upgrade() {
        IndexUpgrader upgrader = new IndexUpgrader(getDirectory(), Version.LUCENE_44);
        try {
            upgrader.upgrade();
        } catch (IOException e) {
            log.error("Error", e);
            return ExceptionUtils.getStackTrace(e);
        }
        return "successful";
    }

    public boolean showInResults(String entityName) {
        EntityDescr descr = getDescrByName().get(entityName);
        return descr != null && descr.isShow();
    }

    @Override
    public void deleteIndexForEntity(String entityName) {
        boolean locked = writeLock.tryLock();
        if (!locked) {
            throw new IllegalStateException("Unable to delete index: writing at the moment");
        }
        LuceneWriter writer = new LuceneWriter(getDirectory());
        try {
            writing = true;
            writer.deleteIndexForEntity(entityName);
        } finally {
            writer.close();
            writeLock.unlock();
            writing = false;
        }
    }

    @Override
    public void deleteIndex() {
        boolean locked = writeLock.tryLock();
        if (!locked) {
            throw new IllegalStateException("Unable to delete index: writing at the moment");
        }
        LuceneWriter writer = new LuceneWriter(getDirectory());
        try {
            writing = true;
            writer.deleteAll();
        } finally {
            writer.close();
            writeLock.unlock();
            writing = false;
        }
    }

    @Override
    public int reindexEntity(String entityName) {
        int count = 0;

        MetaClass metaClass = metadata.getSession().getClass(entityName);
        if (metaClass == null)
            throw new IllegalArgumentException("MetaClass not found for " + entityName);

        Transaction tx = persistence.createTransaction();
        try {
            FtsSender sender = AppBeans.get(FtsSender.NAME);

            sender.emptyQueue(entityName);
            tx.commitRetaining();

            EntityDescr descr = getDescrByName().get(entityName);
            if (descr == null)
                return count;

            EntityManager em = persistence.getEntityManager();

            if (StringUtils.isBlank(descr.getSearchableIfScript())) {
                Query q = em.createQuery("select e.id from " + entityName + " e");
                List<UUID> list = q.getResultList();
                for (UUID id : list) {
                    sender.enqueue(entityName, id, FtsChangeType.INSERT);
                    count++;
                }
            } else {
                Query q = em.createQuery("select e from " + entityName + " e");
                List<BaseEntity> list = q.getResultList();
                for (BaseEntity entity : list) {
                    if (runSearchableIf(entity, descr)) {
                        sender.enqueue(entityName, (UUID) entity.getId(), FtsChangeType.INSERT);
                        count++;
                    }
                }
            }
            tx.commit();
        } finally {
            tx.end();
        }
        return count;
    }

    @Override
    public int reindexAll() {
        int count = 0;
        for (String entityName : getDescrByName().keySet()) {
            count += reindexEntity(entityName);
        }
        return count;
    }

    public Directory getDirectory() {
        if (directory == null) {
            synchronized (this) {
                if (directory == null) {
                    String dir = config.getIndexDir();
                    if (StringUtils.isBlank(dir)) {
                        Configuration configuration = AppBeans.get(Configuration.NAME);
                        dir = configuration.getConfig(GlobalConfig.class).getDataDir() + "/ftsindex";
                    }
                    File file = new File(dir);
                    if (!file.exists()) {
                        boolean b = file.mkdirs();
                        if (!b)
                            throw new RuntimeException("Directory " + dir + " doesn't exist and can not be created");
                    }
                    try {
                        directory = FSDirectory.open(file);

                        if (directory.fileExists("write.lock")) {
                            directory.deleteFile("write.lock");
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return directory;
    }
}
