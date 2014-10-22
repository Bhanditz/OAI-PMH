package com.ontotext.oai.europeana.db.solr;

import com.ontotext.oai.europeana.DataSet;
import com.ontotext.oai.europeana.RegistryInfo;
import com.ontotext.oai.europeana.db.CloseableIterator;
import com.ontotext.oai.europeana.db.RecordsRegistry;
import com.ontotext.oai.europeana.db.SetsProvider;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import java.util.*;

import static com.ontotext.oai.europeana.db.solr.FieldNames.*;

/**
 * Created by Simo on 4.6.2014 г..
 */
public class SolrRegistry implements RecordsRegistry, SetsProvider {
    private static final Log log = LogFactory.getLog(SolrRegistry.class);
    HttpSolrServer server;
    final int rows;
    private final int maxStart;

    private RegistryInfoCache cache = new RegistryInfoCache();

    public SolrRegistry(Properties properties) {
        String baseUrl = properties.getProperty("SolrRegistry.server", "http://data2.eanadev.org:9191/solr");
        server = new HttpSolrServer(baseUrl);
        DefaultHttpClient client = (DefaultHttpClient) server.getHttpClient();
        client.setHttpRequestRetryHandler(new HttpRetryHandler());
        rows = Integer.parseInt(properties.getProperty("MongoDbCatalog.recordsPerPage", "1000"));
        maxStart = Integer.parseInt(properties.getProperty("SolrRegistry.maxStart", "100000"));
    }

    @Override
    public RegistryInfo getRegistryInfo(String recordId) {
        RegistryInfo registryInfo = cache.get(recordId);

        if (registryInfo != null) {
            log.trace("Cached");
            return registryInfo;
        }

        log.debug("Cache miss");

        try {
            SolrQuery query = SolrQueryBuilder.getById(recordId);
            QueryResponse response = server.query(query);
            SolrDocumentList result = response.getResults();
            if  (result.size() != 1) {
                log.warn("Record not found: " + recordId);
            } else {
                SolrDocument document = result.get(0);
                registryInfo = toRegistryInfo(document, null);
            }
        } catch (SolrServerException e) {
            log.fatal("Error executing Solr query", e);
            throw new RuntimeException(e);
        }

        return registryInfo;
    }

    @Override
    public CloseableIterator<RegistryInfo> listRecords(Date from, Date until, String collectionName) {
        SolrQuery query = SolrQueryBuilder.listRecords(from, until, collectionName, rows);
        return cache.add(new QueryIterator(query, collectionName, until));
    }

    @Override
    public Iterator<DataSet> listSets() {
        SolrQuery query = SolrQueryBuilder.listSets();
        return new FacetIterator(query);
    }

    @Override
    public void close() {

    }

    private static RegistryInfo toRegistryInfo(SolrDocument document, String collectionName) {
        String cid = null;
        if (collectionName != null) {
            cid = collectionName;
        } else {
            ArrayList<String> arr = (ArrayList<String>)document.getFieldValue(COLLECTION_NAME);
            if (!arr.isEmpty()) {
                cid = arr.get(0);
                cid = StringEscapeUtils.escapeXml(cid);
            } else {
                log.fatal("Collection name is missing!");
            }
        }
        String eid = (String) document.getFieldValue(EID);
        Date timestamp = (Date) document.getFieldValue(TIMESTAMP);
        final boolean deleted = false;

        return new RegistryInfo(cid,  eid,  timestamp,  deleted);
    }

    protected class QueryIterator implements CloseableIterator<RegistryInfo> {
        private final SolrQuery query;
        private final String fixed_cid; // used to reduce result fields when query has collectionId filter
        private final Date dateUntil;
        SolrDocumentList resultList;
        int currentIndex;
        private RegistryInfo last;

        public QueryIterator(SolrQuery query, String cid, Date dateUntil) {
            this.query = query;
            this.dateUntil = dateUntil;
            this.fixed_cid = StringEscapeUtils.escapeXml(cid);
            getMore(0);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean hasNext() {
            if (currentIndex < resultList.size()) {
                return true;
            }

            return getMore(query.getStart() + query.getRows());
        }

        @Override
        public RegistryInfo next() {
            SolrDocument document = resultList.get(currentIndex++);
            last = toRegistryInfo(document, fixed_cid);
            return last;
        }

        @Override
        public void remove() {

        }

        public RegistryInfo last() {
            return last;
        }

        private boolean getMore(int start) {
            if (start > maxStart) {
                return regenQuery();
            }

            query.setStart(start);
            return fetch();
        }

        private boolean regenQuery() {
            if (last != null) {
                log.debug("regenQuery");
                SolrQueryBuilder.setFilter(query, fixed_cid, last.last_checked, dateUntil);
                query.setStart(0);
                if (fetch()) {
                    skip(last.eid);
                    return currentIndex != resultList.size();
                }
            }
            log.warn("regenQuery failed");
            return false;
        }

        private boolean fetch() {
            try {
                log.trace("Getting more records");
                QueryResponse response = server.query(query);
                log.trace("Getting more records finished.");
                resultList = response.getResults();
                currentIndex = 0;
                return resultList.size() != 0;
            } catch (SolrServerException e) {
                log.fatal("Error executing Solr query", e);
                throw new RuntimeException(e);
            }
        }

        private void skip(String eid) {
            for (int i = 0; i != resultList.size(); ++i) {
                SolrDocument document = resultList.get(i);
                RegistryInfo registryInfo = toRegistryInfo(document, fixed_cid);
                if (eid.equals(registryInfo.eid)) {
                    currentIndex = i + 1;
                    break;
                }
            }
        }

    }

    private class FacetIterator implements CloseableIterator<DataSet> {

        private final SolrQuery query;
        private List<FacetField.Count> names;
        int currentIndex;
        int offset = 0;

        public FacetIterator(SolrQuery query) {
            this.query = query;
            getMore();
        }

        @Override
        public void close() {

        }

        @Override
        public boolean hasNext() {
            if (names != null && currentIndex < names.size()) {
                return true;
            }

            return getMore();
        }

        @Override
        public DataSet next() {
            FacetField.Count nameCount = names.get(currentIndex++);
            String name = nameCount.getName(); // escapeXml in dataSet2Xml
            return new DataSet(name, null); // name is id here
        }

        @Override
        public void remove() {

        }

        private boolean getMore() {
            SolrQueryBuilder.setFacetOffset(query, offset);
            offset += query.getFacetLimit();
            try {
                QueryResponse response = server.query(query);
                FacetField collectionNames = response.getFacetField(COLLECTION_NAME);

                currentIndex = 0;
                names = collectionNames.getValues();
                return !names.isEmpty();
            } catch (SolrServerException e) {
                log.fatal("Error executing Solr query", e);
                throw new RuntimeException(e);
            }
        }
    }

}
