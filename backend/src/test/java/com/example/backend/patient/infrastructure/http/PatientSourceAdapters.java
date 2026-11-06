package com.example.backend.patient.infrastructure.http;

import com.example.backend.patient.domain.PatientStore;
import org.springframework.web.client.RestClient;

/** The production patient port, for the tests above it that read the patient source's mock. */
public final class PatientSourceAdapters {

    private PatientSourceAdapters() {
    }

    /** The HTTP adapter, reading the patient source at {@code baseUrl}. */
    public static PatientStore reading(String baseUrl) {
        return new PatientSourceAdapter(RestClient.builder(), baseUrl);
    }
}
