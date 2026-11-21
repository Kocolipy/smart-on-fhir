package com.example.backend.patient.controller;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;

/**
 * Base FHIR R5's conformance resources, from the same three packages
 * {@code DefaultProfileValidationSupport} loads for R5 — the core, the extensions and the
 * terminology — but a ValueSet, a CodeSystem or any other resource parsed only when the validator
 * asks for it by its canonical URL. The default support parses every resource of all three
 * packages up front, which is most of the time a validation of a {@code Patient} or an
 * {@code OperationOutcome} takes. The StructureDefinitions alone are parsed in full, because the
 * validator lists them all as it is built ({@link #fetchAllStructureDefinitions}).
 *
 * <p>Nothing is left out: every lookup by URL reaches every resource the packages hold, and the
 * list of StructureDefinitions is all of theirs. Every other {@code fetchAll…} throws rather than
 * answer with the few parsed so far — a validation that relied on one would fail loudly, never pass
 * on a partial set.
 */
final class LazyR5ValidationSupport implements IValidationSupport {

    /** {@code DefaultProfileValidationSupportNpmStrategy}'s packages for R5, from the same jar. */
    private static final List<String> PACKAGES = List.of(
            "org/hl7/fhir/r5/packages/hl7.fhir.r5.core-5.0.0.tgz",
            "org/hl7/fhir/r5/packages/hl7.fhir.uv.extensions.r5-1.0.0.tgz",
            "org/hl7/fhir/r5/packages/hl7.terminology-5.1.0.tgz");

    private final FhirContext context;

    private final List<NpmPackage> packages = new ArrayList<>();

    /** Every StructureDefinition the packages hold, once {@link #fetchAllStructureDefinitions} has run. */
    private List<IBaseResource> structureDefinitions;

    /** Every resource asked for, by type and canonical URL, whether a package holds it or not. */
    private final Map<String, Optional<IBaseResource>> fetched = new HashMap<>();

    LazyR5ValidationSupport(FhirContext context) {
        this.context = context;
        for (String name : PACKAGES) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                if (in == null) {
                    throw new IllegalStateException("no R5 package on the classpath: " + name);
                }
                packages.add(NpmPackage.fromPackage(in));
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    @Override
    public FhirContext getFhirContext() {
        return context;
    }

    @Override
    public String getName() {
        return "base R5 packages, parsed on demand";
    }

    @Override
    public IBaseResource fetchStructureDefinition(String url) {
        return fetch("StructureDefinition", url);
    }

    @Override
    public IBaseResource fetchValueSet(String url) {
        return fetch("ValueSet", url);
    }

    @Override
    public IBaseResource fetchCodeSystem(String url) {
        return fetch("CodeSystem", url);
    }

    @Override
    public <T extends IBaseResource> T fetchResource(Class<T> type, String url) {
        if (type == null) {
            return null;
        }
        IBaseResource resource = fetch(context.getResourceType(type), url);
        return type.isInstance(resource) ? type.cast(resource) : null;
    }

    @Override
    public boolean isCodeSystemSupported(ValidationSupportContext support, String system) {
        return fetchCodeSystem(system) != null;
    }

    @Override
    public boolean isValueSetSupported(ValidationSupportContext support, String url) {
        return fetchValueSet(url) != null;
    }

    @Override
    public List<IBaseResource> fetchAllConformanceResources() {
        throw new UnsupportedOperationException(getName() + " cannot list every resource");
    }

    /**
     * Every StructureDefinition of the three packages, parsed on the first call: the validator's
     * FHIRPath engine reads the whole set of types as it is built, so these alone are parsed in
     * full.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends IBaseResource> List<T> fetchAllStructureDefinitions() {
        if (structureDefinitions == null) {
            List<IBaseResource> all = new ArrayList<>();
            for (NpmPackage npm : packages) {
                try {
                    for (String file : npm.listResources("StructureDefinition")) {
                        try (InputStream in = npm.loadResource(file)) {
                            all.add(context.newJsonParser().parseResource(in));
                        }
                    }
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
            }
            structureDefinitions = List.copyOf(all);
        }
        return (List<T>) structureDefinitions;
    }

    @Override
    public <T extends IBaseResource> List<T> fetchAllNonBaseStructureDefinitions() {
        throw new UnsupportedOperationException(getName() + " cannot list every resource");
    }

    @Override
    public <T extends IBaseResource> List<T> fetchAllSearchParameters() {
        throw new UnsupportedOperationException(getName() + " cannot list every resource");
    }

    /**
     * The resource of {@code resourceType} that {@code url} names — {@code url|version} for one
     * version of it — from the first package that holds it, or {@code null} when none does.
     */
    private IBaseResource fetch(String resourceType, String url) {
        if (url == null) {
            return null;
        }
        String key = resourceType + " " + url;
        Optional<IBaseResource> known = fetched.get(key);
        if (known == null) {
            known = Optional.ofNullable(load(resourceType, url));
            fetched.put(key, known);
        }
        return known.orElse(null);
    }

    private IBaseResource load(String resourceType, String url) {
        int bar = url.indexOf('|');
        String canonical = bar < 0 ? url : url.substring(0, bar);
        for (NpmPackage npm : packages) {
            try (InputStream in = bar < 0
                    ? npm.loadByCanonical(canonical)
                    : npm.loadByCanonicalVersion(canonical, url.substring(bar + 1))) {
                if (in != null) {
                    IBaseResource resource = context.newJsonParser().parseResource(in);
                    if (context.getResourceType(resource).equals(resourceType)) {
                        return resource;
                    }
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        return null;
    }
}
