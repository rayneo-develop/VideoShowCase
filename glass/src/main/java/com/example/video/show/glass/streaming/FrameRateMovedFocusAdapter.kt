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
 * Frame rate list (works with [RecyclerViewFocusTracker]).
 */
class FrameRateMovedFocusAdapter(
    private val isLeft: Boolean,
    private val favoriteTracker: RecyclerViewFocusTracker
) : RecyclerView.Adapter<FrameRateMovedFocusAdapter.VH>() {

    private val mData = arrayListOf<Int>()

    fun setData(data: List<Int>) {
        mData.clear()
        mData.addAll(data)
        notifyDataSetChanged()
    }

    fun getCurrentFps(): Int? {
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
        val fps = mData[position]
        holder.tvLabel.text = "$fps fps"

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
