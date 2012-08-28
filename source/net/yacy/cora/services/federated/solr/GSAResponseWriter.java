/**
 *  GSAResponseWriter
 *  Copyright 2012 by Michael Peter Christen
 *  First released 14.08.2012 at http://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.cora.services.federated.solr;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.yacy.cora.protocol.HeaderFramework;
import net.yacy.peers.operation.yacyVersion;
import net.yacy.search.Switchboard;
import net.yacy.search.index.YaCySchema;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.XML;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.SolrIndexSearcher;

/**
 * implementation of a GSA search result.
 * example: GET /gsa/searchresult?q=chicken+teriyaki&output=xml&client=test&site=test&sort=date:D:S:d1
 * for a xml reference, see https://developers.google.com/search-appliance/documentation/68/xml_reference
 */
public class GSAResponseWriter implements QueryResponseWriter {

    private static String YaCyVer = null;
    private static final char lb = '\n';
    private enum GSAToken {
        CACHE_LAST_MODIFIED, // Date that the document was crawled, as specified in the Date HTTP header when the document was crawled for this index.
        CRAWLDATE,  // An optional element that shows the date when the page was crawled. It is shown only for pages that have been crawled within the past two days.
        U,          // The URL of the search result.
        UE,         // The URL-encoded version of the URL that is in the U parameter.
        GD,         // Contains the description of a KeyMatch result..
        T,          // The title of the search result.
        RK,         // Provides a ranking number used internally by the search appliance.
        ENT_SOURCE, // Identifies the application ID (serial number) of the search appliance that contributes to a result. Example: <ENT_SOURCE>S5-KUB000F0ADETLA</ENT_SOURCE>
        FS,         // Additional details about the search result.
        S,          // The snippet for the search result. Query terms appear in bold in the results. Line breaks are included for proper text wrapping.
        LANG,       // Indicates the language of the search result. The LANG element contains a two-letter language code.
        HAS;        // Encapsulates special features that are included for this search result.
    }


    private static final char[] XML_START = (
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<GSP VER=\"3.2\">\n").toCharArray();
    private static final char[] XML_STOP = "</GSP>\n".toCharArray();

    // define a list of simple YaCySchema -> RSS Token matchings
    private static final Map<String, String> field2tag = new HashMap<String, String>();

    // pre-select a set of YaCy schema fields for the solr searcher which should cause a better caching
    private static final YaCySchema[] extrafields = new YaCySchema[]{
        YaCySchema.id, YaCySchema.title, YaCySchema.description, YaCySchema.text_t,
        YaCySchema.h1_txt, YaCySchema.h2_txt, YaCySchema.h3_txt, YaCySchema.h4_txt, YaCySchema.h5_txt, YaCySchema.h6_txt,
        };
    private static final Set<String> SOLR_FIELDS = new HashSet<String>();
    static {
        field2tag.put(YaCySchema.language_s.name(), GSAToken.LANG.name());
        SOLR_FIELDS.addAll(field2tag.keySet());
        for (YaCySchema field: extrafields) SOLR_FIELDS.add(field.name());
    }

    private static class ResHead {
        public int offset, rows, numFound;
        //public int status, QTime;
        //public String df, q, wt;
        //public float maxScore;
    }

    public static class Sort {
        public String sort = null, action = null, direction = null, mode = null, format = null;
        public Sort(String d) {
            this.sort = d;
            String[] s = d.split(":");
            if (s.length != 4) return;
            this.action = s[0]; // date
            this.direction = s[1]; // A or D
            this.mode = s[2]; // S, R, L
            this.format = s[3]; // d1
        }
        public String toSolr() {
            if ("date".equals(this.action)) {
                return YaCySchema.last_modified.name() + " " + (("D".equals(this.direction) ? "desc" : "asc"));
            }
            return null;
        }
    }

    public GSAResponseWriter() {
        super();
    }

    @Override
    public String getContentType(final SolrQueryRequest request, final SolrQueryResponse response) {
        return CONTENT_TYPE_XML_UTF8;
    }

    @Override
    public void init(@SuppressWarnings("rawtypes") NamedList n) {
    }

    @Override
    public void write(final Writer writer, final SolrQueryRequest request, final SolrQueryResponse rsp) throws IOException {
        assert rsp.getValues().get("responseHeader") != null;
        assert rsp.getValues().get("response") != null;

        long start = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        SimpleOrderedMap<Object> responseHeader = (SimpleOrderedMap<Object>) rsp.getResponseHeader();
        DocSlice response = (DocSlice) rsp.getValues().get("response");
        @SuppressWarnings("unchecked")
        SimpleOrderedMap<Object> highlighting = (SimpleOrderedMap<Object>) rsp.getValues().get("highlighting");
        Map<String, List<String>> snippets = OpensearchResponseWriter.highlighting(highlighting);
        Map<Object,Object> context = request.getContext();

        // parse response header
        ResHead resHead = new ResHead();
        NamedList<?> val0 = (NamedList<?>) responseHeader.get("params");
        resHead.rows = Integer.parseInt((String) val0.get("rows"));
        resHead.offset = response.offset(); // equal to 'start'
        resHead.numFound = response.matches();
        //resHead.df = (String) val0.get("df");
        //resHead.q = (String) val0.get("q");
        //resHead.wt = (String) val0.get("wt");
        //resHead.status = (Integer) responseHeader.get("status");
        //resHead.QTime = (Integer) responseHeader.get("QTime");
        //resHead.maxScore = response.maxScore();

        // write header
        writer.write(XML_START);
        OpensearchResponseWriter.solitaireTag(writer, "TM", Long.toString(System.currentTimeMillis() - start));
        OpensearchResponseWriter.solitaireTag(writer, "Q", request.getParams().get("q"));
        paramTag(writer, "sort", (String) context.get("sort"));
        paramTag(writer, "output", "xml_no_dtd");
        paramTag(writer, "ie", "UTF-8");
        paramTag(writer, "oe", "UTF-8");
        paramTag(writer, "client", (String) context.get("client"));
        paramTag(writer, "q", request.getParams().get("q"));
        paramTag(writer, "site", (String) context.get("site"));
        paramTag(writer, "start", Integer.toString(resHead.offset));
        paramTag(writer, "num", Integer.toString(resHead.rows));
        paramTag(writer, "ip", (String) context.get("ip"));
        paramTag(writer, "access", (String) context.get("access")); // p - search only public content, s - search only secure content, a - search all content, both public and secure
        paramTag(writer, "entqr", (String) context.get("entqr")); // query expansion policy; (entqr=1) -- Uses only the search appliance's synonym file, (entqr=1) -- Uses only the search appliance's synonym file, (entqr=3) -- Uses both standard and local synonym files.

        // body introduction
        final int responseCount = response.size();
        writer.write("<RES SN=\"" + (resHead.offset + 1) + "\" EN=\"" + (resHead.offset + responseCount) + "\">"); writer.write(lb); // The index (1-based) of the first and last search result returned in this result set.
        writer.write("<M>" + response.matches() + "</M>"); writer.write(lb); // The estimated total number of results for the search.
        writer.write("<FI/>"); writer.write(lb); // Indicates that document filtering was performed during this search.
        writer.write("<NB><NU>");
        XML.escapeCharData("/search?q=" + request.getParams().get("q") + "&site=" + (String) context.get("site") +
                     "&lr=&ie=UTF-8&oe=UTF-8&output=xml_no_dtd&client=" + (String) context.get("client") + "&access=" + (String) context.get("access") +
                     "&sort=" + (String) context.get("sort") + "&start=" + resHead.offset + responseCount + "&sa=N", writer); // a relative URL pointing to the NEXT results page.
        writer.write("</NU></NB>");
        writer.write(lb);

        // parse body
        SolrIndexSearcher searcher = request.getSearcher();
        DocIterator iterator = response.iterator();
        String urlhash = null;
        for (int i = 0; i < responseCount; i++) {
            writer.write("<R N=\"" + (resHead.offset + i + 1)  + "\"" + (i == 1 ? " L=\"2\"" : "") + ">"); writer.write(lb);
            int id = iterator.nextDoc();
            Document doc = searcher.doc(id, SOLR_FIELDS);
            List<Fieldable> fields = doc.getFields();
            int fieldc = fields.size();
            List<String> texts = new ArrayList<String>();
            String description = "";
            int size = 0;
            for (int j = 0; j < fieldc; j++) {
                Fieldable value = fields.get(j);
                String fieldName = value.name();

                // apply generic matching rule
                String stag = field2tag.get(fieldName);
                if (stag != null) {
                    OpensearchResponseWriter.solitaireTag(writer, stag, value.stringValue());
                    continue;
                }

                // if the rule is not generic, use the specific here
                if (YaCySchema.id.name().equals(fieldName)) {
                    urlhash = value.stringValue();
                    continue;
                }
                if (YaCySchema.sku.name().equals(fieldName)) {
                    OpensearchResponseWriter.solitaireTag(writer, GSAToken.U.name(), value.stringValue());
                    OpensearchResponseWriter.solitaireTag(writer, GSAToken.UE.name(), value.stringValue());
                    continue;
                }
                if (YaCySchema.title.name().equals(fieldName)) {
                    OpensearchResponseWriter.solitaireTag(writer, GSAToken.T.name(), value.stringValue());
                    texts.add(value.stringValue());
                    continue;
                }
                if (YaCySchema.description.name().equals(fieldName)) {
                    description = value.stringValue();
                    texts.add(description);
                    continue;
                }
                if (YaCySchema.last_modified.name().equals(fieldName)) {
                    Date d = new Date(Long.parseLong(value.stringValue()));
                    OpensearchResponseWriter.solitaireTag(writer, GSAToken.CACHE_LAST_MODIFIED.name(), HeaderFramework.formatRFC1123(d));
                    texts.add(value.stringValue());
                    continue;
                }
                if (YaCySchema.load_date_dt.name().equals(fieldName)) {
                    Date d = new Date(Long.parseLong(value.stringValue()));
                    OpensearchResponseWriter.solitaireTag(writer, GSAToken.CRAWLDATE.name(), HeaderFramework.formatRFC1123(d));
                    texts.add(value.stringValue());
                    continue;
                }
                if (YaCySchema.text_t.name().equals(fieldName)) {
                    texts.add(value.stringValue());
                    continue;
                }
                if (YaCySchema.h1_txt.name().equals(fieldName) || YaCySchema.h2_txt.name().equals(fieldName) ||
                    YaCySchema.h3_txt.name().equals(fieldName) || YaCySchema.h4_txt.name().equals(fieldName) ||
                    YaCySchema.h5_txt.name().equals(fieldName) || YaCySchema.h6_txt.name().equals(fieldName)) {
                    // because these are multi-valued fields, there can be several of each
                    texts.add(value.stringValue());
                    continue;
                }
                if (YaCySchema.size_i.name().equals(fieldName)) {
                    size = Integer.parseInt(value.stringValue());
                    continue;
                }
            }
            // compute snippet from texts
            List<String> snippet = urlhash == null ? null : snippets.get(urlhash);
            OpensearchResponseWriter.solitaireTag(writer, GSAToken.S.name(), snippet == null || snippet.size() == 0 ? description : snippet.get(0));
            OpensearchResponseWriter.solitaireTag(writer, GSAToken.GD.name(), description);
            writer.write("<HAS><L/><C SZ=\""); writer.write(Integer.toString(size / 1024)); writer.write("k\" CID=\""); writer.write(urlhash); writer.write("\" ENC=\"UTF-8\"/></HAS>");
            if (YaCyVer == null) YaCyVer = yacyVersion.thisVersion().getName() + "/" + Switchboard.getSwitchboard().peers.mySeed().hash;
            OpensearchResponseWriter.solitaireTag(writer, GSAToken.ENT_SOURCE.name(), YaCyVer);
            OpensearchResponseWriter.closeTag(writer, "R");
        }
        writer.write("</RES>"); writer.write(lb);
        writer.write(XML_STOP);
    }

    public static void paramTag(final Writer writer, final String tagname, String value) throws IOException {
        if (value == null || value.length() == 0) return;
        writer.write("<PARAM name=\"");
        writer.write(tagname);
        writer.write("\" value=\"");
        XML.escapeCharData(value, writer);
        writer.write("\" original_value=\"");
        writer.write(value);
        writer.write("\"/>"); writer.write(lb);
    }
}