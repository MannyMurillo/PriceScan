package com.pricescan.app.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pricescan.app.R
import com.pricescan.app.databinding.ItemPriceRowBinding
import com.pricescan.app.model.StorePrice

class PriceAdapter(
    private var prices: List<StorePrice> = emptyList()
) : RecyclerView.Adapter<PriceAdapter.PriceViewHolder>() {

    fun updatePrices(newPrices: List<StorePrice>) {
        this.prices = newPrices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PriceViewHolder {
        val binding = ItemPriceRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PriceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PriceViewHolder, position: Int) {
        holder.bind(prices[position], position == 0)
    }

    override fun getItemCount(): Int = prices.size

    class PriceViewHolder(private val binding: ItemPriceRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StorePrice, isCheapest: Boolean) {
            val context = binding.root.context

            binding.tvStoreEmoji.text = item.emoji
            binding.tvStoreName.text  = item.store
            binding.tvStoreNote.text  = item.note.ifEmpty { item.store.lowercase() + ".com" }
            binding.tvPriceAmount.text = item.formattedPrice
            binding.tvPriceUnit.text   = item.unit

            // Show AI badge if it's an AI estimated price
            binding.tvAiBadge.visibility = if (item.isAiEstimate) View.VISIBLE else View.GONE

            // Highlight the cheapest offer
            if (isCheapest) {
                binding.priceRowCard.setBackgroundResource(R.drawable.price_item_best_bg)
                binding.tvPriceAmount.setTextColor(ContextCompat.getColor(context, R.color.accent))
                binding.tvPriceAmount.textSize = 20f
                binding.tvPriceAmount.setTypeface(null, Typeface.BOLD)
            } else {
                binding.priceRowCard.setBackgroundResource(R.drawable.price_item_bg)
                binding.tvPriceAmount.setTextColor(ContextCompat.getColor(context, R.color.text))
                binding.tvPriceAmount.textSize = 17f
                binding.tvPriceAmount.setTypeface(null, Typeface.NORMAL)
            }
        }
    }
}
