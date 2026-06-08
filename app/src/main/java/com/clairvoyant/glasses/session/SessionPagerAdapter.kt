package com.clairvoyant.glasses.session

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Maps the current ordered list of session ids to pages. Stable item ids (derived from the
 * session id) let ViewPager2 keep existing pages when the set grows.
 */
class SessionPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var ids: List<String> = emptyList()

    fun submit(newIds: List<String>) {
        if (newIds == ids) return
        ids = newIds.toList()
        notifyDataSetChanged()
    }

    fun idAt(position: Int): String? = ids.getOrNull(position)
    fun indexOf(sessionId: String): Int = ids.indexOf(sessionId)

    override fun getItemCount() = ids.size
    override fun getItemId(position: Int) = ids[position].hashCode().toLong()
    override fun containsItem(itemId: Long) = ids.any { it.hashCode().toLong() == itemId }
    override fun createFragment(position: Int) = SessionFragment.newInstance(ids[position])
}
