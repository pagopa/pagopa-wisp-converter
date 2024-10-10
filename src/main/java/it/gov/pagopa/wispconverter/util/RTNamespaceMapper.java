package it.gov.pagopa.wispconverter.util;


import org.glassfish.jaxb.runtime.marshaller.NamespacePrefixMapper;

public class RTNamespaceMapper extends NamespacePrefixMapper {

    private static final String PAY_I_PREFIX = "pay_i";
    private static final String PAY_I_URI = "http://www.digitpa.gov.it/schemas/2011/Pagamenti/";
    @Override
    public String getPreferredPrefix(String namespaceUri, String suggestion, boolean requirePrefix) {
        return PAY_I_PREFIX;
    }

        @Override
    public String[] getPreDeclaredNamespaceUris() {
        return new String[] { PAY_I_URI };
    }

}
