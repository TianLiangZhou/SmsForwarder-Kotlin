package com.idormy.sms.forwarder.ui.rule

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.idormy.sms.forwarder.MainActivity
import com.idormy.sms.forwarder.R
import com.idormy.sms.forwarder.RuleConfigActivity
import com.idormy.sms.forwarder.ScannerActivity
import com.idormy.sms.forwarder.adapter.BaseAdapter
import com.idormy.sms.forwarder.adapter.RuleAdapter
import com.idormy.sms.forwarder.data.Empty
import com.idormy.sms.forwarder.databinding.FragmentRuleBinding
import com.idormy.sms.forwarder.db.model.Rule
import com.idormy.sms.forwarder.db.model.Sender
import com.idormy.sms.forwarder.provider.Core
import com.idormy.sms.forwarder.ui.ProgressToolbarFragment
import com.idormy.sms.forwarder.utilities.Action
import com.idormy.sms.forwarder.utilities.Status
import com.idormy.sms.forwarder.view.RuleViewModel
import com.idormy.sms.forwarder.view.RuleViewModelFactory
import com.idormy.sms.forwarder.view.SenderViewModel
import com.idormy.sms.forwarder.view.SenderViewModelFactory
import com.idormy.sms.forwarder.widget.AlertDialogFragment
import com.idormy.sms.forwarder.widget.UndoSnackbarManager
import com.idormy.sms.forwarder.widget.observe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

class RuleFragment : ProgressToolbarFragment(), Toolbar.OnMenuItemClickListener, DialogInterface.OnClickListener, BaseAdapter.Listener<Rule> {

    private var _binding: FragmentRuleBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var senderList: List<Sender> = emptyList()

    private val ruleViewModel: RuleViewModel by activityViewModels {
        RuleViewModelFactory(Core.rule)
    }

    private val senderViewModel: SenderViewModel by activityViewModels {
        SenderViewModelFactory(Core.sender)
    }

    private val adapter: RuleAdapter by lazy { RuleAdapter() }

    private val undoManager: UndoSnackbarManager<Rule> by lazy {
        UndoSnackbarManager(requireActivity() as MainActivity, adapter::undo, adapter::commit)
    }

    private val main: MainActivity by lazy { requireActivity() as MainActivity }


    private val permReqLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startConfig(Rule())
    }

    private val scannerResultDialog by lazy {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("扫描结果")
            .setPositiveButton("导入", this)
            .setNeutralButton("取消", this)
            .setCancelable(false)
            .create()
    }

    private var scannedResult: String? = null

    private val startScannerForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.hasExtra("scanned_result") == true) {
            scannedResult = result.data?.getStringExtra("scanned_result")
            scannerResultDialog.setMessage(
                if (scannedResult!!.startsWith("{") ||  scannedResult!!.startsWith("["))
                    JSONObject(scannedResult!!).toString(4)
                else scannedResult
            )
            scannerResultDialog.show()
        }
    }

    class NoSenderConfirmationDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.no_sender_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as MainActivity).navigateToSender()
            }
            setNegativeButton(R.string.no, null)
        }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentRuleBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.inflateMenu(R.menu.config_manager_menu)
        toolbar.setOnMenuItemClickListener(this)
        toolbar.setTitle(R.string.rule_setting)
        val listView = binding.ruleListView
        adapter.listener = this
        listView.layoutManager = LinearLayoutManager(requireActivity(), RecyclerView.VERTICAL, false)
        listView.adapter = adapter
        listView.addItemDecoration(DividerItemDecoration(context, (listView.layoutManager as LinearLayoutManager).orientation))
        Core.rule.listener = adapter
        with(senderViewModel) {
            observe(senders) { list ->
                adapter.senderList = list
                senderList = list
            }
            loadAllSender()
        }
        with(ruleViewModel) {
            observe(progressLiveEvent) { show ->
                if (show) (activity as MainActivity).showProgress()
                else (activity as MainActivity).hideProgress()
            }
            observe(errorMessage) { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            observe(rules) {
                adapter.submitList(it)
            }
            loadRules()
        }
        ItemTouchHelper(TouchCallback()).attachToRecyclerView(listView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        Core.rule.listener = null
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.action_scan_qr_code -> {
                startScannerForResult.launch(Intent(context, ScannerActivity::class.java))
                true
            }
            R.id.action_manual_settings -> {
                if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    startConfig(Rule())
                } else {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.READ_PHONE_STATE)) {
                        main.snackbar("赋予电话卡读取权限").setAction("允许") {
                            permReqLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }.show()
                    } else {
                        permReqLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                    }
                }
                true
            }
            else -> false
        }
    }
    private fun startConfig(item: Rule) {
        if (senderList.isEmpty()) {
            NoSenderConfirmationDialogFragment().apply {
                arg(Empty())
            }.show(parentFragmentManager, null)
            return
        }
        Core.dataStore.serialize(item)
        startActivity(
            Intent(requireContext(), RuleConfigActivity::class.java)
                .putExtra(Action.ruleId, item.id)
                .putParcelableArrayListExtra(Action.senders, senderList as ArrayList<Sender>)
        )
    }

    override fun onEditor(item: Rule) {
        startConfig(item)
    }

    override fun onCopy(item: Rule) {
        val duplicate = item.copy()
        duplicate.id = 0
        duplicate.name += "[副本]"
        ruleViewModel.save(duplicate)
    }

    override fun onShare(item: Rule) {
        if (!parentFragmentManager.isStateSaved) {
            val encodeToString: String = Json.encodeToString(item)
            MainActivity.QRCodeDialog(encodeToString).show(parentFragmentManager, null)
        }
    }

    override fun onDelete(item: Rule) {
        if (item.id > 0) {
            ruleViewModel.delete(item.id)
        }
    }

    override fun onClick(view: View, item: Rule) {
        view.isSelected = !view.isSelected
        item.status = if (view.isSelected) Status.On.value else Status.Off.value
        ruleViewModel.save(item)
    }

    override fun onLongClick(view: View, item: Rule): Boolean {
        return false
    }

    inner class TouchCallback: ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        ItemTouchHelper.START) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val index = viewHolder.adapterPosition
            adapter.remove(index)
            undoManager.remove(Pair(index, (viewHolder as RuleAdapter.RuleViewHolder).item))
        }
    }

    override fun onClick(p0: DialogInterface?, flag: Int) {
        if (flag != DialogInterface.BUTTON_POSITIVE) {
            return
        }
        if (scannedResult.isNullOrEmpty()) {
            return
        }
        if (!scannedResult!!.startsWith("{")) {
            return
        }
        val rule = Json.decodeFromString(Rule.serializer(), scannedResult!!)
        rule.id = 0
        ruleViewModel.save(rule)
    }



}