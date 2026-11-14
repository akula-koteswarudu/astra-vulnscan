package com.vulnscan.ingest;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * XML parsing hardened against XXE and entity expansion (the decompiled app is untrusted input):
 * DOCTYPE declarations rejected, external entities off, secure processing on, no entity expansion.
 * Namespace-aware, so attributes are looked up by namespace URI, never by prefix text.
 */
public final class SecureXml {

    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private SecureXml() {
    }

    public static Document parse(Path file) {
        try {
            return builder().parse(file.toFile());
        } catch (SAXException | IOException e) {
            throw new IllegalArgumentException("cannot parse XML " + file + ": " + e.getMessage(), e);
        }
    }

    public static Document parse(String xml) {
        try {
            return builder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        } catch (SAXException | IOException e) {
            throw new IllegalArgumentException("cannot parse XML: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser does not support secure configuration", e);
        }
    }

    /** android:&lt;name&gt; attribute, or null when absent. */
    public static String androidAttr(Element e, String name) {
        return e.hasAttributeNS(ANDROID_NS, name) ? e.getAttributeNS(ANDROID_NS, name) : null;
    }

    public static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element el && localName.equals(el.getLocalName() != null ? el.getLocalName() : el.getTagName())) {
                out.add(el);
            }
        }
        return out;
    }

    public static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el) {
                out.add(el);
            }
        }
        return out;
    }

    public static String localName(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    /** Serialises an element back to XML text (used for manifest slices). */
    public static String toXml(Node node) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(w));
            // Drop the namespace declaration the serialiser repeats on every detached element.
            return w.toString().replace(" xmlns:android=\"" + ANDROID_NS + "\"", "").strip();
        } catch (TransformerException e) {
            throw new IllegalStateException("cannot serialise XML", e);
        }
    }
}
