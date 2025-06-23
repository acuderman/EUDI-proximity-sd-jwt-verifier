package com.eudi.verifier

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.eudi.verifier.databinding.ActivityVerificationResultBinding
import com.eudi.verifier.models.VPToken
import com.eudi.verifier.adapters.CredentialAttributeAdapter
import com.google.gson.Gson

class VerificationResultActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityVerificationResultBinding
    private lateinit var attributeAdapter: CredentialAttributeAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerificationResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        
        val vpTokenJson = intent.getStringExtra("vp_token")
        if (vpTokenJson != null) {
            try {
                val vpToken = Gson().fromJson(vpTokenJson, VPToken::class.java)
                displayVerificationResult(vpToken)
            } catch (e: Exception) {
                displayError("Invalid VP Token format")
            }
        } else {
            displayError("No VP Token received")
        }
    }
    
    private fun setupUI() {
        binding.apply {
            btnClose.setOnClickListener {
                finish()
            }
            
            recyclerViewAttributes.layoutManager = LinearLayoutManager(this@VerificationResultActivity)
            attributeAdapter = CredentialAttributeAdapter()
            recyclerViewAttributes.adapter = attributeAdapter
        }
    }
    
    private fun displayVerificationResult(vpToken: VPToken) {
        binding.apply {
            tvVerificationStatus.text = "✓ Verification Successful"
            tvVerificationStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            
            val presentation = vpToken.verifiablePresentation
            tvHolderInfo.text = "Holder: ${presentation.holder ?: "Unknown"}"
            
            val credentials = presentation.verifiableCredential
            if (credentials.isNotEmpty()) {
                val firstCredential = credentials.first()
                val subject = firstCredential.credentialSubject
                
                tvCredentialType.text = "Credential Type: ${firstCredential.type.joinToString(", ")}"
                
                val attributes = mutableListOf<Pair<String, String>>()
                
                subject.givenName?.let { attributes.add("Given Name" to it) }
                subject.familyName?.let { attributes.add("Family Name" to it) }
                subject.dateOfBirth?.let { attributes.add("Date of Birth" to it) }
                subject.address?.let { 
                    attributes.add("Address" to "${it.streetAddress}, ${it.addressLocality}, ${it.postalCode}")
                }
                
                attributeAdapter.updateAttributes(attributes)
                
                tvCredentialIssuer.text = "Issued by: ${firstCredential.issuer}"
                tvIssuanceDate.text = "Issued: ${firstCredential.issuanceDate}"
                firstCredential.expirationDate?.let {
                    tvExpirationDate.text = "Expires: $it"
                }
            }
        }
    }
    
    private fun displayError(message: String) {
        binding.apply {
            tvVerificationStatus.text = "✗ Verification Failed"
            tvVerificationStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            tvHolderInfo.text = "Error: $message"
            
            tvCredentialType.text = ""
            tvCredentialIssuer.text = ""
            tvIssuanceDate.text = ""
            tvExpirationDate.text = ""
        }
    }
}