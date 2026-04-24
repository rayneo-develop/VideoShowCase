package com.example.video.show.glass.streaming

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.video.show.glass.R
import com.ffalcon.mercury.android.sdk.core.make3DEffectForSide
import com.ffalcon.mercury.android.sdk.ui.util.RecyclerViewFocusTracker

/**
 * Based on x3 MovedFocusPosAdapter: works with [RecyclerViewFocusTracker] to display the selected item.
 */
class ResolutionMovedFocusAdapter(
    private val isLeft: Boolean,
    private val favoriteTracker: RecyclerViewFocusTracker
) : RecyclerView.Adapter<ResolutionMovedFocusAdapter.VH>() {

    private val mData = arrayListOf<Pair<Int, Int>>()

    fun setData(data: List<Pair<Int, Int>>) {
        mData.clear()
        mData.addAll(data)
        notifyDataSetChanged()
    }

    fun getCurrentResolution(): Pair<Int, Int>? {
        val curPos = favoriteTracker.checkedSelectPos()
        if (curPos < 0 || curPos > mData.size - 1) return null
        return mData[curPos]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_resolution_moved, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = mData.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (w, h) = mData[position]
        val label = when {
            h == 1080 && w >= 1920 -> "${w}×${h}（1080P）"
            h == 720 && w >= 1280 -> "${w}×${h}（720P）"
            else -> "${w}×${h}"
        }
        holder.tvLabel.text = label

        val isSelectedPos = favoriteTracker.checkPosSelected(position)
        make3DEffectForSide(holder.itemView, isLeft, isSelectedPos)
        holder.itemView.alpha = if (isSelectedPos) 1f else 0.65f
        if (isSelectedPos) {
            holder.itemView.setBackgroundResource(R.drawable.bg_resolution_selected)
        } else {
            holder.itemView.background = null
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLabel: TextView = itemView.findViewById(R.id.tv_label)
    }
}
