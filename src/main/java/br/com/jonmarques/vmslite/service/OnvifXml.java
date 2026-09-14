package br.com.jonmarques.vmslite.service;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

final class OnvifXml {
    private OnvifXml() {}

    static Element parse(String xml) {
        if (xml == null || xml.isBlank()) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            return document.getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    static List<Element> elements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        var nodes = parent.getElementsByTagNameNS("*", name);
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }

    static String text(Element parent, String name) {
        List<Element> elements = elements(parent, name);
        return elements.isEmpty() ? null : elements.get(0).getTextContent().trim();
    }

    static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String deviceUuid(Element root) {
        for (Element address : elements(root, "Address")) {
            String text = address.getTextContent().trim();
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("urn:uuid:")) return text.substring(9);
            if (lower.startsWith("uuid:")) return text.substring(5);
        }
        return null;
    }
}
