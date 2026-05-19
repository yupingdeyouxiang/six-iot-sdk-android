package com.six.iot.ui.user

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.six.iam.AuthManager
import com.six.iot.MainActivity
import com.six.iot.UserUtil
import com.six.iot.databinding.FragmentUserBinding

class UserFragment : Fragment() {

    companion object {
        private const val TAG = "UserFragment"
    }

    private var _binding: FragmentUserBinding? = null
    private val binding get() = _binding!!

    // Define the Runnable as a property so we can cancel it.
    private val updateUiRunnable = Runnable { updateUiState() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUserBinding.inflate(inflater, container, false)
        binding.userSwipeRefreshLayout.setOnRefreshListener {
            updateUiState()
        }
        binding.signOutBtn.setOnClickListener {
            (activity as? MainActivity)?.signOut()
            // Use the same robust pattern for the sign-out action.
            view?.removeCallbacks(updateUiRunnable) // Remove any existing callbacks
            view?.postDelayed(updateUiRunnable, 200)
        }
        binding.loginButton.setOnClickListener {
            (activity as? MainActivity)?.startAuth()
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Hide both panes initially to prevent flicker before the auth state is known.
        binding.profileGroup.visibility = View.GONE
        binding.loginCard.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        view?.postDelayed(updateUiRunnable, 200)
    }

    override fun onPause() {
        super.onPause()
        // Also cancel the runnable in onPause for extra safety.
        view?.removeCallbacks(updateUiRunnable)
    }

    private fun updateUiState() {
        // The primary guard for this race condition is now canceling the runnable.
        // This null check remains as a final layer of safety.
        if (_binding == null) {
            return
        }
        try {
            binding.userSwipeRefreshLayout.isRefreshing = true
            val context = context ?: return
            if (AuthManager.authenticated(context)) {
                binding.profileGroup.visibility = View.VISIBLE
                binding.loginCard.visibility = View.GONE
                decodeAndDisplayUserId()
            } else {
                binding.profileGroup.visibility = View.GONE
                binding.loginCard.visibility = View.VISIBLE
            }
            binding.userSwipeRefreshLayout.isRefreshing = false
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI state", e)
            // Ensure the refreshing indicator is always turned off, even on error.
            if (_binding != null) {
                binding.userSwipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun decodeAndDisplayUserId() {
        val context = context ?: return
        try {
            val idToken = AuthManager.authenticatedIdToken(context)
            binding.userIdText.text = UserUtil.parseSubFromIdToken(idToken)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding ID token", e)
            binding.userIdText.text = "Error"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The most critical part: cancel any pending UI update when the view is destroyed.
        view?.removeCallbacks(updateUiRunnable)
        _binding = null
    }
}
