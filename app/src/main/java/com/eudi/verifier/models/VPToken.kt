package com.eudi.verifier.models

import com.google.gson.annotations.SerializedName
import java.util.*

data class VPToken(
    @SerializedName("vp")
    val verifiablePresentation: VerifiablePresentation
)

data class VerifiablePresentation(
    @SerializedName("@context")
    val context: List<String> = listOf(
        "https://www.w3.org/2018/credentials/v1",
        "https://w3id.org/security/suites/jws-2020/v1"
    ),
    val type: List<String> = listOf("VerifiablePresentation"),
    val id: String = "urn:uuid:${UUID.randomUUID()}",
    val holder: String,
    val verifiableCredential: List<VerifiableCredential>,
    val proof: Proof? = null
)

data class VerifiableCredential(
    @SerializedName("@context")
    val context: List<String> = listOf(
        "https://www.w3.org/2018/credentials/v1",
        "https://w3id.org/citizenship/v1"
    ),
    val type: List<String> = listOf("VerifiableCredential", "EUDICredential"),
    val id: String,
    val issuer: String,
    val issuanceDate: String,
    val expirationDate: String? = null,
    val credentialSubject: CredentialSubject,
    val proof: Proof
)

data class CredentialSubject(
    val id: String,
    val type: List<String> = listOf("EUCitizen"),
    val givenName: String,
    val familyName: String,
    val dateOfBirth: String,
    val placeOfBirth: String? = null,
    val address: Address? = null,
    val nationality: String? = null,
    val documentNumber: String? = null
)

data class Address(
    val streetAddress: String,
    val addressLocality: String,
    val postalCode: String,
    val addressCountry: String
)

data class Proof(
    val type: String = "JsonWebSignature2020",
    val created: String,
    val verificationMethod: String,
    val proofPurpose: String = "assertionMethod",
    val jws: String
)

// Presentation Definition for requesting specific credentials
data class PresentationDefinition(
    val id: String = "eudi-verification-${UUID.randomUUID()}",
    val name: String = "EUDI Identity Verification",
    val purpose: String = "Verify identity information for access control",
    val input_descriptors: List<InputDescriptor>
)

data class InputDescriptor(
    val id: String,
    val name: String,
    val purpose: String,
    val constraints: Constraints
)

data class Constraints(
    val fields: List<Field>
)

data class Field(
    val path: List<String>,
    val filter: Filter? = null
)

data class Filter(
    val type: String = "string",
    val const: String? = null
)

// BLE Engagement Data
data class BLEEngagementData(
    val deviceMac: String,
    val serviceUuid: String,
    val characteristicUuid: String,
    val timestamp: Long = System.currentTimeMillis()
)

// Verification Request
data class VerificationRequest(
    val id: String = UUID.randomUUID().toString(),
    val verifierDid: String,
    val verifierName: String,
    val presentationDefinition: PresentationDefinition,
    val nonce: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

// Verification Result
data class VerificationResult(
    val isValid: Boolean,
    val verifiedCredentials: List<VerifiableCredential>,
    val holderDid: String,
    val verificationTime: String,
    val errors: List<String> = emptyList()
)