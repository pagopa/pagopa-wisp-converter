package it.gov.pagopa.wispconverter.util;

import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
@Component
public class ReceiptRequestHandler extends DefaultHandler {
	
    private static final String NOTICE_NUMBER = "noticeNumber";
    private static final String FISCAL_CODE = "fiscalCode";
    private static final String CREDITOR_REFERENCE_ID = "creditorReferenceId";
	
	private PaSendRTV2Request paSendRTV2Request;
	private StringBuilder elementValue;
	
	@Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (elementValue == null) {
            elementValue = new StringBuilder();
        } else {
            elementValue.append(ch, start, length);
        }
    }
	
	@Override
    public void startDocument() throws SAXException {
		paSendRTV2Request = new PaSendRTV2Request();
    }
	
	@Override
	public void startElement(String uri, String lName, String qName, Attributes attr) throws SAXException {
		switch (qName) {
		case NOTICE_NUMBER:
			elementValue = new StringBuilder();
			break;
		case FISCAL_CODE:
			elementValue = new StringBuilder();
			break;
		case CREDITOR_REFERENCE_ID:
			elementValue = new StringBuilder();
			break;
		default:
			break;
		}
	}

	@Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        switch (qName) {
            case NOTICE_NUMBER:
            	paSendRTV2Request.setNoticeNumber(elementValue.toString());
                break;
            case FISCAL_CODE:
            	paSendRTV2Request.setFiscalCode(elementValue.toString());
                break;
            case CREDITOR_REFERENCE_ID:
            	paSendRTV2Request.setCreditorReferenceId(elementValue.toString());
    			break;
    		default:
    			break;
        }
    }
	
	@Data
	public class PaSendRTV2Request{
	    private String noticeNumber;
	    private String fiscalCode;
	    private String creditorReferenceId;   
	}

}
