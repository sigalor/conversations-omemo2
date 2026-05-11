package eu.siacs.conversations.services;

import java.security.cert.CertificateException;

public class DaneEnforcementException extends CertificateException {
    public DaneEnforcementException(String message) {
        super(message);
    }
}
