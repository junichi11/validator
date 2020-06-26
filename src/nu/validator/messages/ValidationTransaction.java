package nu.validator.messages;

import com.thaiopensource.relaxng.impl.CombineValidator;
import com.thaiopensource.util.PropertyMap;
import com.thaiopensource.validate.IncorrectSchemaException;
import com.thaiopensource.validate.Schema;
import com.thaiopensource.validate.SchemaReader;
import com.thaiopensource.validate.SchemaResolver;
import com.thaiopensource.validate.Validator;
import com.thaiopensource.validate.auto.AutoSchemaReader;
import com.thaiopensource.validate.prop.wrap.WrapProperty;
import com.thaiopensource.validate.rng.CompactSchemaReader;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import nu.validator.checker.XmlPiChecker;
import nu.validator.checker.jing.CheckerSchema;
import nu.validator.htmlparser.common.DocumentMode;
import nu.validator.htmlparser.common.DocumentModeHandler;
import nu.validator.htmlparser.sax.HtmlParser;
import nu.validator.localentities.LocalCacheEntityResolver;
import nu.validator.spec.Spec;
import nu.validator.xml.TypedInputSource;
import org.xml.sax.ContentHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;

/**
 * This class code was mainly extracted from the original class
 * {@link VerifierServletTransaction}.
 *
 * @author hsivonen, mfukala@netbeans.org
 */
public class ValidationTransaction implements DocumentModeHandler, SchemaResolver {

    private static final Logger LOGGER = Logger.getLogger(ValidationTransaction.class.getCanonicalName());

    // XXX SVG!!!
    private static final String[] KNOWN_CONTENT_TYPES = {
        "application/atom+xml", "application/docbook+xml",
        "application/xhtml+xml", "application/xv+xml", "image/svg+xml"};
    private static final String[] NAMESPACES_FOR_KNOWN_CONTENT_TYPES = {
        "http://www.w3.org/2005/Atom", "http://docbook.org/ns/docbook",
        "http://www.w3.org/1999/xhtml", "http://www.w3.org/1999/xhtml",
        "http://www.w3.org/2000/svg"};
    protected static final String[] ALL_CHECKERS = {
        "http://c.validator.nu/table/", "http://c.validator.nu/nfc/",
        "http://c.validator.nu/text-content/",
        "http://c.validator.nu/unchecked/",
        "http://c.validator.nu/usemap/", "http://c.validator.nu/obsolete/",
        "http://c.validator.nu/xml-pi/"};
    private static final String[] ALL_CHECKERS_HTML4 = {
        "http://c.validator.nu/table/", "http://c.validator.nu/nfc/",
        "http://c.validator.nu/unchecked/", "http://c.validator.nu/usemap/"};

    protected BufferingRootNamespaceSniffer bufferingRootNamespaceSniffer = null;
    protected boolean rootNamespaceSeen = false;
    protected String contentType = null;

    protected static int[] presetDoctypes;
    protected static String[] presetLabels;
    protected static String[] presetUrls;
    protected static String[] presetNamespaces;

    protected MessageEmitterAdapter errorHandler;
    protected static String[] preloadedSchemaUrls;
    protected static Schema[] preloadedSchemas;

    private Map<String, Validator> loadedValidatorUrls = new HashMap<String, Validator>();

    protected Validator validator = null;
    protected LocalCacheEntityResolver entityResolver;

    private static final Pattern SPACE = Pattern.compile("\\s+");
    protected static final int HTML5_SCHEMA = 3;
    protected static final int XHTML1STRICT_SCHEMA = 2;
    protected static final int XHTML1FRAMESET_SCHEMA = 4;
    protected static final int XHTML1TRANSITIONAL_SCHEMA = 1;
    protected static final int XHTML5_SCHEMA = 7;

    public HtmlParser htmlParser = null;
    protected PropertyMap jingPropertyMap;
    protected static Spec html5spec;

    protected XMLReader reader;
    protected LexicalHandler lexicalHandler;

    public void rootNamespace(String namespace, Locator locator) throws SAXException {
        if (validator == null) {
            int index = -1;
            for (int i = 0; i < presetNamespaces.length; i++) {
                if (namespace.equals(presetNamespaces[i])) {
                    index = i;
                    break;
                }
            }
            if (index == -1) {
                String message = "Cannot find preset schema for namespace: \u201C"
                        + namespace + "\u201D.";
                SAXException se = new SAXException(message);
                errorHandler.schemaError(se);
                throw se;
            }
            String label = presetLabels[index];
            String urls = presetUrls[index];
            errorHandler.info("Using the preset for " + label
                    + " based on the root namespace " + namespace);
            try {
                validator = validatorByUrls(urls);
            } catch (IOException ioe) {
                // At this point the schema comes from memory.
                throw new RuntimeException(ioe);
            } catch (IncorrectSchemaException e) {
                // At this point the schema comes from memory.
                throw new RuntimeException(e);
            }
            if (bufferingRootNamespaceSniffer == null) {
                throw new RuntimeException(
                        "Bug! bufferingRootNamespaceSniffer was null.");
            }
            bufferingRootNamespaceSniffer.setContentHandler(validator.getContentHandler());
        }

        if (!rootNamespaceSeen) {
            rootNamespaceSeen = true;
            if (contentType != null) {
                int i;
                if ((i = Arrays.binarySearch(KNOWN_CONTENT_TYPES, contentType)) > -1) {
                    if (!NAMESPACES_FOR_KNOWN_CONTENT_TYPES[i].equals(namespace)) {
                        String message = "".equals(namespace) ? "\u201C"
                                + contentType
                                + "\u201D is not an appropriate Content-Type for a document whose root element is not in a namespace."
                                : "\u201C"
                                + contentType
                                + "\u201D is not an appropriate Content-Type for a document whose root namespace is \u201C"
                                + namespace + "\u201D.";
                        SAXParseException spe = new SAXParseException(message,
                                locator);
                        errorHandler.warning(spe);
                    }
                }
            }
        }
    }

    @Override
    public void documentMode(DocumentMode mode, String publicIdentifier,
            String systemIdentifier) throws SAXException {
        documentMode(mode, publicIdentifier, systemIdentifier, false);
    }

    public void documentMode(DocumentMode mode, String publicIdentifier,
            String systemIdentifier, boolean html4SpecificAdditionalErrorChecks)
            throws SAXException {
        if (validator == null) {
            try {
                if ("-//W3C//DTD XHTML 1.0 Transitional//EN".equals(publicIdentifier)) {
                    errorHandler.info("XHTML 1.0 Transitional doctype seen. Appendix C is not supported. Proceeding anyway for your convenience. The parser is still an HTML parser, so namespace processing is not performed and \u201Cxml:*\u201D attributes are not supported. Using the schema for "
                            + getPresetLabel(XHTML1TRANSITIONAL_SCHEMA)
                            + "."
                            + (html4SpecificAdditionalErrorChecks ? " HTML4-specific tokenization errors are enabled."
                                    : ""));
                    validator = validatorByDoctype(XHTML1TRANSITIONAL_SCHEMA);
                } else if ("-//W3C//DTD XHTML 1.0 Strict//EN".equals(publicIdentifier)) {
                    errorHandler.info("XHTML 1.0 Strict doctype seen. Appendix C is not supported. Proceeding anyway for your convenience. The parser is still an HTML parser, so namespace processing is not performed and \u201Cxml:*\u201D attributes are not supported. Using the schema for "
                            + getPresetLabel(XHTML1STRICT_SCHEMA)
                            + "."
                            + (html4SpecificAdditionalErrorChecks ? " HTML4-specific tokenization errors are enabled."
                                    : ""));
                    validator = validatorByDoctype(XHTML1STRICT_SCHEMA);
                } else if ("-//W3C//DTD HTML 4.01 Transitional//EN".equals(publicIdentifier)) {
                    errorHandler.info("HTML 4.01 Transitional doctype seen. Using the schema for "
                            + getPresetLabel(XHTML1TRANSITIONAL_SCHEMA)
                            + "."
                            + (html4SpecificAdditionalErrorChecks ? ""
                                    : " HTML4-specific tokenization errors are not enabled."));
                    validator = validatorByDoctype(XHTML1TRANSITIONAL_SCHEMA);
                } else if ("-//W3C//DTD HTML 4.01//EN".equals(publicIdentifier)) {
                    errorHandler.info("HTML 4.01 Strict doctype seen. Using the schema for "
                            + getPresetLabel(XHTML1STRICT_SCHEMA)
                            + "."
                            + (html4SpecificAdditionalErrorChecks ? ""
                                    : " HTML4-specific tokenization errors are not enabled."));
                    validator = validatorByDoctype(XHTML1STRICT_SCHEMA);
                } else if ("-//W3C//DTD HTML 4.0 Transitional//EN".equals(publicIdentifier)) {
                    errorHandler.info("Legacy HTML 4.0 Transitional doctype seen.  Please consider using HTML 4.01 Transitional instead. Proceeding anyway for your convenience with the schema for "
                            + getPresetLabel(XHTML1TRANSITIONAL_SCHEMA)
                            + "."
                            + (html4SpecificAdditionalErrorChecks ? ""
                                    : " HTML4-specific tokenization errors are not enabled."));
                    validator = validatorByDoctype(XHTML1TRANSITIONAL_SCHEMA);
                } else if ("-//W3C//DTD HTML 4.0//EN".equals(publicIdentifier)) {
                    errorHandler.info("Legacy HTML 4.0 Strict doctype seen. Please consider using HTML 4.01 instead. Proceeding anyway for your convenience with the schema for "
                            + getPresetLabel(XHTML1STRICT_SCHEMA)
                            + "."
                            + (html4SpecificAdditionalErrorChecks ? ""
                                    : " HTML4-specific tokenization errors are not enabled."));
                    validator = validatorByDoctype(XHTML1STRICT_SCHEMA);
                } else {
                    errorHandler.info("Using the schema for "
                            + getPresetLabel(HTML5_SCHEMA)
                            + "."
                            + (html4SpecificAdditionalErrorChecks ? " HTML4-specific tokenization errors are enabled."
                                    : ""));
                    validator = validatorByDoctype(HTML5_SCHEMA);
                }
            } catch (IOException ioe) {
                // At this point the schema comes from memory.
                throw new RuntimeException(ioe);
            } catch (IncorrectSchemaException e) {
                // At this point the schema comes from memory.
                throw new RuntimeException(e);
            }
            ContentHandler ch = validator.getContentHandler();
            ch.setDocumentLocator(htmlParser.getDocumentLocator());
            ch.startDocument();
            reader.setContentHandler(ch);
        } else {
            if (html4SpecificAdditionalErrorChecks) {
                errorHandler.info("HTML4-specific tokenization errors are enabled.");
            }
        }
    }

    public Schema resolveSchema(String url, PropertyMap options)
            throws SAXException, IOException, IncorrectSchemaException {
        int i = Arrays.binarySearch(preloadedSchemaUrls, url);
        if (i > -1) {
            Schema rv = preloadedSchemas[i];
            if (options.contains(WrapProperty.ATTRIBUTE_OWNER)) {
                if (rv instanceof ValidationTransaction.ProxySchema && ((ValidationTransaction.ProxySchema) rv).getWrappedSchema() instanceof CheckerSchema) {
                    errorHandler.error(new SAXParseException(
                            "A non-schema checker cannot be used as an attribute schema.",
                            null, url, -1, -1));
                    throw new IncorrectSchemaException();
                } else {
                    // ugly fall through
                }
            } else {
                return rv;
            }
        }

        //this code line should not normally be encountered since the necessary
        //schemas have been preloaded
        LOGGER.log(Level.INFO, "Going to create a non preloaded Schema for {0}", url); //NOI18N

        TypedInputSource schemaInput = (TypedInputSource) entityResolver.resolveEntity(
                null, url);
        SchemaReader sr = null;
        if ("application/relax-ng-compact-syntax".equals(schemaInput.getType())) {
            sr = CompactSchemaReader.getInstance();
        } else {
            sr = new AutoSchemaReader();
        }
        Schema sch = sr.createSchema(schemaInput, options);
        return sch;
    }

    /**
     * @param validator
     * @return
     * @throws SAXException
     * @throws IOException
     * @throws IncorrectSchemaException
     */
    protected Validator validatorByUrls(String schemaList) throws SAXException,
            IOException, IncorrectSchemaException {
        Validator v = null;
        String[] schemas = SPACE.split(schemaList);
        for (int i = schemas.length - 1; i > -1; i--) {
            String url = schemas[i];
            if ("http://c.validator.nu/all/".equals(url)
                    || "http://hsivonen.iki.fi/checkers/all/".equals(url)) {
                for (int j = 0; j < ALL_CHECKERS.length; j++) {
                    v = combineValidatorByUrl(v, ALL_CHECKERS[j]);
                }
            } else if ("http://c.validator.nu/all-html4/".equals(url)
                    || "http://hsivonen.iki.fi/checkers/all-html4/".equals(url)) {
                for (int j = 0; j < ALL_CHECKERS_HTML4.length; j++) {
                    v = combineValidatorByUrl(v, ALL_CHECKERS_HTML4[j]);
                }
            } else {
                v = combineValidatorByUrl(v, url);
            }
        }
        return v;
    }

    /**
     * @param val
     * @param url
     * @return
     * @throws SAXException
     * @throws IOException
     * @throws IncorrectSchemaException
     */
    private Validator combineValidatorByUrl(Validator val, String url)
            throws SAXException, IOException, IncorrectSchemaException {
        if (!"".equals(url)) {
            Validator v = validatorByUrl(url);
            if (val == null) {
                val = v;
            } else {
                val = new CombineValidator(v, val);
            }
        }
        return val;
    }

    /**
     * @param url
     * @return
     * @throws SAXException
     * @throws IOException
     * @throws IncorrectSchemaException
     */
    private Validator validatorByUrl(String url) throws SAXException,
            IOException, IncorrectSchemaException {
        Validator v = loadedValidatorUrls.get(url);
        if (v != null) {
            return v;
        }

        if ("http://s.validator.nu/html5/html5full-aria.rnc".equals(url)
                || "http://s.validator.nu/xhtml5-aria-rdf-svg-mathml.rnc".equals(url)
                || "http://s.validator.nu/html5/html5full.rnc".equals(url)
                || "http://s.validator.nu/html5/xhtml5full-xhtml.rnc".equals(url)
                || "http://s.validator.nu/html5-aria-svg-mathml.rnc".equals(url)) {
            errorHandler.setSpec(html5spec);
        }
        Schema sch = resolveSchema(url, jingPropertyMap);
        Validator validatorInstance = sch.createValidator(jingPropertyMap);
        if (validatorInstance.getContentHandler() instanceof XmlPiChecker) {
            lexicalHandler = (LexicalHandler) validatorInstance.getContentHandler();
        }

        loadedValidatorUrls.put(url, v);
        return validatorInstance;
    }

    private String getPresetLabel(int schemaId) {
        for (int i = 0; i < presetDoctypes.length; i++) {
            if (presetDoctypes[i] == schemaId) {
                return presetLabels[i];
            }
        }
        return "unknown";
    }

    protected Validator validatorByDoctype(int schemaId) throws SAXException,
            IOException, IncorrectSchemaException {
        if (schemaId == 0) {
            return null;
        }
        for (int i = 0; i < presetDoctypes.length; i++) {
            if (presetDoctypes[i] == schemaId) {
                return validatorByUrls(presetUrls[i]);
            }
        }
        throw new RuntimeException("Doctype mappings not initialized properly.");
    }

    /**
     * @param url
     * @return
     * @throws SAXException
     * @throws IOException
     * @throws IncorrectSchemaException
     */
    private static Schema schemaByUrl(String url, EntityResolver resolver,
            PropertyMap pMap) throws SAXException, IOException,
            IncorrectSchemaException {
        LOGGER.fine(String.format("Will load schema: %s", url));
        long a = System.currentTimeMillis();
        TypedInputSource schemaInput;
        try {
            schemaInput = (TypedInputSource) resolver.resolveEntity(
                    null, url);
        } catch (ClassCastException e) {
            LOGGER.log(Level.SEVERE, url, e);
            throw e;
        }

        SchemaReader sr = null;
        if ("application/relax-ng-compact-syntax".equals(schemaInput.getType())) {
            sr = CompactSchemaReader.getInstance();
            LOGGER.log(Level.FINE, "Used CompactSchemaReader");
        } else {
            sr = new AutoSchemaReader();
            LOGGER.log(Level.FINE, "Used AutoSchemaReader");
        }
        long c = System.currentTimeMillis();

        Schema sch = sr.createSchema(schemaInput, pMap);
        LOGGER.log(Level.FINE, String.format("Schema created in %s ms.", (System.currentTimeMillis() - c)));
        return sch;
    }

    protected static Schema proxySchemaByUrl(String uri, EntityResolver resolver, PropertyMap pMap) {
        return new ProxySchema(uri, resolver, pMap);
    }

    /**
     * A Schema instance delegate, the delegated instance if softly reachable so
     * it should not be GCed so often. If the delegate is GCed a new instance is
     * recreated.
     */
    private static class ProxySchema implements Schema {

        private String uri;
        private EntityResolver resolver;
        private PropertyMap pMap;

        private SoftReference<Schema> delegateWeakRef;

        private ProxySchema(String uri, EntityResolver resolver, PropertyMap pMap) {
            this.uri = uri;
            this.resolver = resolver;
            this.pMap = pMap;
        }

        //exposing just because of some instanceof test used in the code
        private Schema getWrappedSchema() throws SAXException, IOException, IncorrectSchemaException {
            return getSchemaDelegate();
        }

        public Validator createValidator(PropertyMap pm) {
            try {
                return getSchemaDelegate().createValidator(pm);
            } catch (Exception ex) { //SAXException, IOException, IncorrectSchemaException
                LOGGER.log(Level.INFO, "Cannot create schema delegate", ex); //NOI18N
            }
            return null;
        }

        public PropertyMap getProperties() {
            try {
                return getSchemaDelegate().getProperties();
            } catch (Exception ex) { //SAXException, IOException, IncorrectSchemaException
                LOGGER.log(Level.INFO, "Cannot create schema delegate", ex); //NOI18N
            }
            return null;
        }

        private synchronized Schema getSchemaDelegate() throws SAXException, IOException, IncorrectSchemaException {
            Schema delegate = delegateWeakRef != null ? delegateWeakRef.get() : null;
            if (delegate == null) {
                long a = System.currentTimeMillis();
                delegate = schemaByUrl(uri, resolver, pMap);
                long b = System.currentTimeMillis();
                delegateWeakRef = new SoftReference<Schema>(delegate);
                LOGGER.log(Level.FINE, "Created new Schema instance for {0} in {1}ms.", new Object[]{uri, (b - a)});
            } else {
                LOGGER.log(Level.FINE, "Using cached Schema instance for {0}", uri);
            }
            return delegate;
        }

    }

}
