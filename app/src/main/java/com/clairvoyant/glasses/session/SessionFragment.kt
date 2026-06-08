package com.clairvoyant.glasses.session

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.clairvoyant.glasses.databinding.FragmentSessionBinding

/**
 * One swipe page = one Claude Code session. Pulls its state from the Activity's [SessionStore],
 * renders the transcript, and shows the permission bar when this session has a pending request.
 * Approve/deny delegate to the Activity, which owns the RelayClient.
 */
class SessionFragment : Fragment() {

    private var _binding: FragmentSessionBinding? = null
    private val binding get() = _binding!!
    private val adapter = TranscriptAdapter()
    private lateinit var sessionId: String

    private val host get() = activity as? SessionHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = requireArguments().getString(ARG_SESSION_ID)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSessionBinding.inflate(inflater, container, false)
        binding.transcriptRecycler.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.transcriptRecycler.adapter = adapter
        binding.btnApprove.setOnClickListener { answer("allow") }
        binding.btnDeny.setOnClickListener { answer("deny") }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        host?.register(sessionId, this)
        refreshTranscript()
        refreshPending()
    }

    override fun onPause() {
        super.onPause()
        host?.unregister(sessionId, this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun refreshTranscript() {
        val b = _binding ?: return
        val items = host?.store?.data(sessionId)?.transcript ?: emptyList()
        adapter.submit(items)
        b.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isNotEmpty()) b.transcriptRecycler.scrollToPosition(items.size - 1)
    }

    fun refreshPending() {
        val b = _binding ?: return
        val pending = host?.store?.data(sessionId)?.pending
        if (pending != null) {
            b.permissionDescription.text = "${pending.tool}: ${pending.description}"
            b.permissionBar.visibility = View.VISIBLE
        } else {
            b.permissionBar.visibility = View.GONE
        }
    }

    /** Scroll the transcript by [dy] px (voice/touch SCROLL commands route here). */
    fun scrollBy(dy: Int) { _binding?.transcriptRecycler?.smoothScrollBy(0, dy) }

    private fun answer(decision: String) {
        val pending = host?.store?.data(sessionId)?.pending ?: return
        host?.answerPermission(sessionId, pending.id, decision)
    }

    companion object {
        private const val ARG_SESSION_ID = "session_id"
        fun newInstance(sessionId: String) = SessionFragment().apply {
            arguments = Bundle().apply { putString(ARG_SESSION_ID, sessionId) }
        }
    }
}

/** Implemented by SessionActivity so fragments can read the store and answer prompts. */
interface SessionHost {
    val store: SessionStore
    fun register(sessionId: String, fragment: SessionFragment)
    fun unregister(sessionId: String, fragment: SessionFragment)
    fun answerPermission(session: String, id: String, decision: String)
}
