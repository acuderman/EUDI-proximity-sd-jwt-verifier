package com.eudi.verifier.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.eudi.verifier.R

class CredentialAttributeAdapter : RecyclerView.Adapter<CredentialAttributeAdapter.AttributeViewHolder>() {
    
    private var attributes: List<Pair<String, String>> = emptyList()
    
    fun updateAttributes(newAttributes: List<Pair<String, String>>) {
        attributes = newAttributes
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttributeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_credential_attribute, parent, false)
        return AttributeViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: AttributeViewHolder, position: Int) {
        val attribute = attributes[position]
        holder.bind(attribute.first, attribute.second)
    }
    
    override fun getItemCount(): Int = attributes.size
    
    class AttributeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAttributeName: TextView = itemView.findViewById(R.id.tv_attribute_name)
        private val tvAttributeValue: TextView = itemView.findViewById(R.id.tv_attribute_value)
        
        fun bind(name: String, value: String) {
            tvAttributeName.text = name
            tvAttributeValue.text = value
        }
    }
}