package com.linecorp.line.auth.fido.fido2.server.helper;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.line.auth.fido.fido2.common.mdsv3.AuthenticatorStatus;
import com.linecorp.line.auth.fido.fido2.common.mdsv3.MetadataBLOBPayload;
import com.linecorp.line.auth.fido.fido2.common.mdsv3.MetadataBLOBPayloadEntry;
import com.linecorp.line.auth.fido.fido2.server.config.MdsInfo;
import com.linecorp.line.auth.fido.fido2.server.entity.MetadataEntity;
import com.linecorp.line.auth.fido.fido2.server.entity.MetadataTocEntity;
import com.linecorp.line.auth.fido.fido2.server.exception.MdsV3MetadataException;
import com.linecorp.line.auth.fido.fido2.server.mds.MetadataTOCResult;
import com.linecorp.line.auth.fido.fido2.server.repository.MetadataRepository;
import com.linecorp.line.auth.fido.fido2.server.repository.MetadataTocRepository;
import com.linecorp.line.auth.fido.fido2.server.util.MdsV3MetadataCertificateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Base64;

@Slf4j
@Component
public class MdsV3MetadataHelper {

    private final MetadataRepository metadataRepository;

    private final MetadataTocRepository metadataTocRepository;

    public MdsV3MetadataHelper(MetadataRepository metadataRepository, MetadataTocRepository metadataTocRepository) {
        this.metadataRepository = metadataRepository;
        this.metadataTocRepository = metadataTocRepository;
    }

    public MetadataTOCResult handle(String url, String metadataToc, MdsInfo mdsInfo) throws CertificateException, MdsV3MetadataException {
        MetadataBLOBPayload metadataBLOBPayload = createMetadataBLOBPayload(metadataToc);

        checkLatestDataExist(metadataTocRepository.findFirstByMetadataSourceOrderByNoDesc(mdsInfo.getName()), metadataBLOBPayload);
        MdsV3MetadataCertificateUtil.verifyCertificate(url, metadataToc, mdsInfo, metadataBLOBPayload);
        return handleMetadata(metadataToc, mdsInfo, metadataBLOBPayload);
    }

    private MetadataTOCResult handleMetadata(String metadataToc, MdsInfo mdsInfo, MetadataBLOBPayload metadataBLOBPayload) {
        saveMetaDataToc(metadataToc, mdsInfo, metadataBLOBPayload, metadataTocRepository);
        return processBlobPayload(metadataBLOBPayload, metadataRepository);
    }

    private static MetadataBLOBPayload createMetadataBLOBPayload(String metadataToc) throws MdsV3MetadataException {

        DecodedJWT decodedJWT = JWT.decode(metadataToc);
        String encodedMetadataTocPayload = decodedJWT.getPayload();

        // decode payload
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MetadataBLOBPayload metadataBLOBPayload;
        try {
            metadataBLOBPayload = objectMapper.readValue(Base64.getUrlDecoder().decode(encodedMetadataTocPayload), MetadataBLOBPayload.class);
        } catch (IOException e) {
            throw new MdsV3MetadataException(MetadataTOCResult
                    .builder()
                    .result(false)
                    .totalCount(0)
                    .updatedCount(0)
                    .reason("Json parsing error of Metadata TOC Payload")
                    .build());
        }
        log.info("Metadata TOC Payload: {}", metadataBLOBPayload);
        return metadataBLOBPayload;
    }

    private static void checkLatestDataExist(MetadataTocEntity metadataTocEntity, MetadataBLOBPayload metadataBLOBPayload) throws MdsV3MetadataException {
        // check no and compare with previous
        if (metadataTocEntity != null && (metadataTocEntity.getId() >= metadataBLOBPayload.getNo())) {
            // already up to date
            throw new MdsV3MetadataException(MetadataTOCResult
                    .builder()
                    .result(false)
                    .totalCount(metadataBLOBPayload.getEntries().size())
                    .updatedCount(0)
                    .reason("Local cached data is already up to date")
                    .build());
        }
    }

    private static void saveMetaDataToc(String metadataToc, MdsInfo mdsInfo, MetadataBLOBPayload metadataBLOBPayload, MetadataTocRepository metadataTocRepository) {
        metadataTocRepository.save(
                new MetadataTocEntity(null, mdsInfo.getName(), metadataBLOBPayload.getNo(), metadataBLOBPayload.getLegalHeader(), metadataBLOBPayload.getNextUpdate(), JWT.decode(metadataToc).getPayload()));
    }

    private static MetadataTOCResult processBlobPayload(MetadataBLOBPayload metadataBLOBPayload, MetadataRepository metadataRepository) {

        // iterate all payload entry
        log.info("MDS Registered metadata count: {}", metadataBLOBPayload.getEntries().size());

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        int updatedCount = 0;
        int uafEntryCount = 0;
        int u2fEntryCount = 0;
        int fido2EntryCount = 0;

        for (MetadataBLOBPayloadEntry entry : metadataBLOBPayload.getEntries()) {

            if (!isAcceptableStatus(entry.getStatusReports().get(0).getStatus())) {
                log.debug("Ignore entry due to status: {}", entry.getStatusReports().get(0).getStatus());
                continue;
            }

            if (isUAFEntry(entry.getAaid())) {
                uafEntryCount++;
                log.debug("Ignore UAF metadata entry");
                continue;
            }

            if (isU2FEntry(entry)) {
                u2fEntryCount++;
            }

            MetadataEntity localMetadataEntity = null;
            if (isFIDO2Entry(entry.getAaguid())) {
                fido2EntryCount++;
                localMetadataEntity = metadataRepository.findByAaguid(entry.getAaguid());
            }

            if (isNewEntry(entry, localMetadataEntity)) {
                updatedCount++;

                try {
                    saveMetadata(entry, localMetadataEntity, objectMapper.writeValueAsString(entry.getMetadataStatement()), metadataRepository,objectMapper);
                } catch (JsonProcessingException e) {
                    log.debug("Json parsing error of Metadata Statement: {}", entry.getMetadataStatement());
                }

            } else {
                log.info("Skip entry, already latest one");
            }
        }

        log.info("Finish handling Metadata TOC");

        return MetadataTOCResult
                .builder()
                .result(true)
                .totalCount(metadataBLOBPayload.getEntries().size())
                .updatedCount(updatedCount)
                .u2fEntryCount(u2fEntryCount)
                .uafEntryCount(uafEntryCount)
                .fido2EntryCount(fido2EntryCount)
                .build();
    }

    private static void saveMetadata(MetadataBLOBPayloadEntry entry, MetadataEntity localMetadataEntity, String encodedMetadataStatement, MetadataRepository metadataRepository, ObjectMapper objectMapper) throws JsonProcessingException {

        MetadataEntity.MetadataEntityBuilder builder = MetadataEntity
                .builder()
                .aaguid(entry.getAaguid())
                .content(encodedMetadataStatement)
                .biometricStatusReports(ObjectUtils.isEmpty(entry.getBiometricStatusReports()) ? null : objectMapper.writeValueAsString(entry.getBiometricStatusReports()))
                .statusReports(ObjectUtils.isEmpty(entry.getStatusReports()) ? null : objectMapper.writeValueAsString(entry.getStatusReports()))
                .timeOfLastStatusChange(entry.getTimeOfLastStatusChange());

        // if it is existing one, just update it
        if (localMetadataEntity != null) {
            builder.id(localMetadataEntity.getId());
        }
        MetadataEntity metadataEntity = builder.build();
        metadataRepository.save(metadataEntity);
    }

    private static boolean isU2FEntry(MetadataBLOBPayloadEntry entry) {
        return entry.getAttestationCertificateKeyIdentifiers() != null &&
                !entry.getAttestationCertificateKeyIdentifiers().isEmpty();
    }

    private static boolean isFIDO2Entry(String aaguid) {
        return aaguid != null;
    }

    private static boolean isUAFEntry(String aaid) {
        return aaid != null;
    }

    private static boolean isNewEntry(MetadataBLOBPayloadEntry entry, MetadataEntity localMetadataEntity) {
        return localMetadataEntity == null || !entry.getTimeOfLastStatusChange().equals(
                localMetadataEntity.getTimeOfLastStatusChange());
    }

    /**
     * check whether the entry is acceptable or not (this is server policy, we implement this function only for conformance tool
     *
     * @param authenticatorStatus
     * @return
     */
    private static boolean isAcceptableStatus(AuthenticatorStatus authenticatorStatus) {
        // check status report, timeOfLastStatusChange has been changed comparing to local cache
        // find metadata from db and compare it
        return authenticatorStatus != AuthenticatorStatus.USER_VERIFICATION_BYPASS &&
                authenticatorStatus != AuthenticatorStatus.ATTESTATION_KEY_COMPROMISE &&
                authenticatorStatus != AuthenticatorStatus.USER_KEY_REMOTE_COMPROMISE &&
                authenticatorStatus != AuthenticatorStatus.USER_KEY_PHYSICAL_COMPROMISE;
    }

}
