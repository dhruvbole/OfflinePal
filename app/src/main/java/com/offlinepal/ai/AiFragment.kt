package com.offlinepal.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.offlinepal.AppDatabase
import com.offlinepal.databinding.FragmentAiBinding
import com.offlinepal.notes.Note
import com.offlinepal.timer.TimerService
import com.offlinepal.todo.TodoItem
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AiFragment : Fragment() {

    private var _binding: FragmentAiBinding? = null
    private val binding get() = _binding!!

    private lateinit var engine: LocalAiEngine
    private lateinit var chatAdapter: ChatAdapter2
    private lateinit var panelAdapter: PanelAdapter2

    private val db by lazy { AppDatabase.getDatabase(requireContext()) }

    private val messages = mutableListOf<ChatMsg>()
    private var currentPanel = PanelType.NONE

    // Timer state
    private val timerMap = mutableMapOf<String, Long>() // id -> remainingSeconds

    private val timerTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra(TimerService.EXTRA_TICK_ID) ?: return
            val remaining = intent.getLongExtra(TimerService.EXTRA_REMAINING_MS, 0L) / 1000
            timerMap[id] = remaining
            if (currentPanel == PanelType.TIMERS) refreshPanel()
        }
    }

    private val timerDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra(TimerService.EXTRA_TICK_ID) ?: return
            timerMap.remove(id)
            if (currentPanel == PanelType.TIMERS) refreshPanel()
            addAiMessage("⏱ Timer done! \uD83C\uDF89")
        }
    }

    enum class PanelType { NONE, TIMERS, ALARMS, NOTES, TODOS }

    data class ChatMsg(
        val id: Long = System.currentTimeMillis(),
        val text: String,
        val isUser: Boolean,
        val isTyping: Boolean = false
    )

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAiBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        engine = LocalAiEngine(requireContext())

        setupChat()
        setupPanel()
        setupInput()
        setupChips()
        initAI()
    }

    private fun initAI() {
        binding.aiStatusBar.visibility = View.VISIBLE
        binding.aiStatusText.text = "Loading AI model..."

        engine.initialize(
            onReady = {
                binding.aiStatusBar.animate().alpha(0f).setDuration(800).withEndAction {
                    binding.aiStatusBar.visibility = View.GONE
                    binding.aiStatusBar.alpha = 1f
                }.start()
                addAiMessage("Hey! \uD83D\uDC4B I'm Pal, your AI assistant. I'm fully loaded and ready.\n\nTry: \"set a 5 minute timer\", \"remind me at 3pm\", \"add milk to my shopping list\", or just ask me anything!")
            },
            onError = { err ->
                binding.aiStatusBar.visibility = View.VISIBLE
                binding.aiStatusText.text = "Smart mode (AI model: $err)"
                binding.aiStatusProgress.visibility = View.GONE
                addAiMessage("Hey! \uD83D\uDC4B I'm Pal. I'm running in smart mode (the AI model had trouble loading). I can still handle timers, alarms, notes, tasks, math, and answer questions. What do you need?")
            }
        )
    }

    // ─── Chat setup ───────────────────────────────────────────────────────────

    private fun setupChat() {
        chatAdapter = ChatAdapter2(messages)
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = chatAdapter
            itemAnimator = null
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
    }

    private fun setupChips() {
        binding.chipTimer.setOnClickListener { showPanel(PanelType.TIMERS) }
        binding.chipAlarm.setOnClickListener { showPanel(PanelType.ALARMS) }
        binding.chipNotes.setOnClickListener { showPanel(PanelType.NOTES) }
        binding.chipTodos.setOnClickListener { showPanel(PanelType.TODOS) }
        binding.btnClosePanel.setOnClickListener { hidePanel() }
        binding.btnClearDone.setOnClickListener {
            lifecycleScope.launch { db.todoDao().deleteAllCompleted() }
            Snackbar.make(binding.root, "Completed tasks cleared", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage() {
        val text = binding.editInput.text.toString().trim()
        if (text.isBlank()) return
        binding.editInput.setText("")
        hideKeyboard()

        // Add user message
        messages.add(ChatMsg(text = text, isUser = true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()

        // Add typing indicator
        val typingMsg = ChatMsg(text = "●  ●  ●", isUser = false, isTyping = true)
        messages.add(typingMsg)
        val typingIdx = messages.size - 1
        chatAdapter.notifyItemInserted(typingIdx)
        scrollToBottom()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { engine.process(text) }

            // Replace typing with actual response
            val idx = messages.indexOf(typingMsg)
            if (idx >= 0) {
                messages[idx] = ChatMsg(text = result.text, isUser = false)
                chatAdapter.notifyItemChanged(idx)
            }
            scrollToBottom()

            // Handle intent
            result.intent?.let { handleIntent(it, text) }
        }
    }

    private fun handleIntent(intent: LocalAiEngine.DetectedIntent, originalInput: String) {
        lifecycleScope.launch {
            when (intent.type) {
                LocalAiEngine.IntentType.SET_TIMER -> {
                    val minutes = intent.params["minutes"]?.toDoubleOrNull() ?: 1.0
                    val seconds = intent.params["seconds"]?.toLongOrNull()
                        ?: (minutes * 60).toLong()
                    val label = intent.params["label"] ?: "Timer"
                    val id = "timer_${System.currentTimeMillis()}"
                    startTimer(id, seconds, label)
                    showPanel(PanelType.TIMERS)
                }
                LocalAiEngine.IntentType.SET_ALARM -> {
                    val hour = intent.params["hour"]?.toIntOrNull() ?: 8
                    val minute = intent.params["minute"]?.toIntOrNull() ?: 0
                    val label = intent.params["label"] ?: "Alarm"
                    scheduleAlarm(hour, minute, label)
                    showPanel(PanelType.ALARMS)
                }
                LocalAiEngine.IntentType.ADD_NOTE -> {
                    val content = intent.params["content"] ?: return@launch
                    withContext(Dispatchers.IO) { db.noteDao().insertNote(Note(title = "Note", content = content)) }
                }
                LocalAiEngine.IntentType.ADD_TODO -> {
                    val task = intent.params["task"] ?: return@launch
                    val cat = intent.params["category"] ?: "general"
                    withContext(Dispatchers.IO) { db.todoDao().insert(TodoItem(title = task, category = cat)) }
                }
                LocalAiEngine.IntentType.SHOW_TIMERS -> showPanel(PanelType.TIMERS)
                LocalAiEngine.IntentType.SHOW_ALARMS -> showPanel(PanelType.ALARMS)
                LocalAiEngine.IntentType.SHOW_NOTES -> showPanel(PanelType.NOTES)
                LocalAiEngine.IntentType.SHOW_TODOS -> showPanel(PanelType.TODOS)
                LocalAiEngine.IntentType.CANCEL_TIMER -> {
                    timerMap.keys.firstOrNull()?.let { id ->
                        cancelTimer(id)
                    }
                }
                else -> {}
            }
        }
    }

    // ─── Timer ────────────────────────────────────────────────────────────────

    private fun startTimer(id: String, seconds: Long, label: String) {
        timerMap[id] = seconds
        val ctx = requireContext()
        val intent = Intent(ctx, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_DURATION_MS, seconds * 1000)
            putExtra(TimerService.EXTRA_TICK_ID, id)
            putExtra("label", label)
        }
        ctx.startService(intent)
    }

    private fun cancelTimer(id: String) {
        timerMap.remove(id)
        val ctx = requireContext()
        val intent = Intent(ctx, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP
            putExtra(TimerService.EXTRA_TICK_ID, id)
        }
        ctx.startService(intent)
        if (currentPanel == PanelType.TIMERS) refreshPanel()
        addAiMessage("Timer cancelled! ✋")
    }

    // ─── Alarm ────────────────────────────────────────────────────────────────

    private fun scheduleAlarm(hour: Int, minute: Int, label: String) {
        val ctx = requireContext()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val displayH = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
        val ampm = if (hour >= 12) "PM" else "AM"
        val timeStr = "%d:%02d %s".format(displayH, minute, ampm)

        val intent = android.content.Intent(ctx, com.offlinepal.timer.TimerService::class.java).apply {
            action = "ALARM_TRIGGER"
            putExtra("label", label)
        }
        val pi = PendingIntent.getService(ctx, (System.currentTimeMillis() % 10000).toInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    // ─── Panel ────────────────────────────────────────────────────────────────

    private fun setupPanel() {
        panelAdapter = PanelAdapter2(
            onCancelTimer = { id -> cancelTimer(id) },
            onDeleteNote = { note -> lifecycleScope.launch(Dispatchers.IO) { db.noteDao().deleteNote(note) }; refreshPanelDelayed() },
            onToggleTodo = { item -> lifecycleScope.launch(Dispatchers.IO) { db.todoDao().update(item.copy(isCompleted = !item.isCompleted)) }; refreshPanelDelayed() },
            onDeleteTodo = { item -> lifecycleScope.launch(Dispatchers.IO) { db.todoDao().delete(item) }; refreshPanelDelayed() }
        )
        binding.recyclerPanel.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = panelAdapter
        }
    }

    private fun showPanel(type: PanelType) {
        currentPanel = type
        binding.panelContainer.visibility = View.VISIBLE
        binding.panelTitle.text = when (type) {
            PanelType.TIMERS -> "⏱ Active Timers"
            PanelType.ALARMS -> "🔔 Scheduled Alarms"
            PanelType.NOTES -> "📝 Notes"
            PanelType.TODOS -> "✅ Tasks"
            else -> ""
        }
        binding.btnClearDone.visibility = if (type == PanelType.TODOS) View.VISIBLE else View.GONE
        refreshPanel()
    }

    private fun hidePanel() {
        currentPanel = PanelType.NONE
        binding.panelContainer.visibility = View.GONE
    }

    private fun refreshPanelDelayed() {
        binding.root.postDelayed({ refreshPanel() }, 200)
    }

    private fun refreshPanel() {
        when (currentPanel) {
            PanelType.TIMERS -> {
                panelAdapter.showTimers(timerMap.toMap())
            }
            PanelType.NOTES -> {
                lifecycleScope.launch {
                    val notes = withContext(Dispatchers.IO) { db.noteDao().getAllNotesList() }
                    panelAdapter.showNotes(notes)
                }
            }
            PanelType.TODOS -> {
                lifecycleScope.launch {
                    val todos = withContext(Dispatchers.IO) { db.todoDao().getAllList() }
                    panelAdapter.showTodos(todos)
                }
            }
            PanelType.ALARMS -> {
                panelAdapter.showAlarms(timerMap) // placeholder - show a simple message
            }
            else -> {}
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun addAiMessage(text: String) {
        messages.add(ChatMsg(text = text, isUser = false))
        chatAdapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.recyclerChat.post {
            if (messages.isNotEmpty()) binding.recyclerChat.smoothScrollToPosition(messages.size - 1)
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editInput.windowToken, 0)
    }

    override fun onStart() {
        super.onStart()
        requireContext().registerReceiver(timerTickReceiver, IntentFilter(TimerService.BROADCAST_TICK))
        requireContext().registerReceiver(timerDoneReceiver, IntentFilter(TimerService.BROADCAST_FINISH))
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(timerTickReceiver) } catch (e: Exception) {}
        try { requireContext().unregisterReceiver(timerDoneReceiver) } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        engine.close()
        _binding = null
    }
}

// ─── Chat Adapter ─────────────────────────────────────────────────────────────

class ChatAdapter2(private val items: List<AiFragment.ChatMsg>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val tf = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun getItemViewType(pos: Int) = if (items[pos].isUser) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (type == 0)
            UserVH(com.offlinepal.databinding.ItemChatUserBinding.inflate(inf, parent, false))
        else
            AiVH(com.offlinepal.databinding.ItemChatAssistantBinding.inflate(inf, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val msg = items[pos]
        when (holder) {
            is UserVH -> { holder.b.textUserMessage.text = msg.text; holder.b.textUserTime.text = tf.format(Date(msg.id)) }
            is AiVH -> {
                holder.b.textAssistantMessage.text = msg.text
                holder.b.progressTyping.visibility = if (msg.isTyping) View.VISIBLE else View.GONE
                holder.b.textAssistantTime.text = tf.format(Date(msg.id))
            }
        }
    }

    inner class UserVH(val b: com.offlinepal.databinding.ItemChatUserBinding) : RecyclerView.ViewHolder(b.root)
    inner class AiVH(val b: com.offlinepal.databinding.ItemChatAssistantBinding) : RecyclerView.ViewHolder(b.root)
}

// ─── Panel Adapter ────────────────────────────────────────────────────────────

class PanelAdapter2(
    private val onCancelTimer: (String) -> Unit,
    private val onDeleteNote: (Note) -> Unit,
    private val onToggleTodo: (TodoItem) -> Unit,
    private val onDeleteTodo: (TodoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Timer(val id: String, val secs: Long) : Item()
        data class NoteRow(val note: Note) : Item()
        data class TodoRow(val item: TodoItem) : Item()
        data class Empty(val msg: String) : Item()
    }

    private val items = mutableListOf<Item>()

    fun showTimers(map: Map<String, Long>) {
        items.clear()
        if (map.isEmpty()) items.add(Item.Empty("No active timers"))
        else map.forEach { (id, secs) -> items.add(Item.Timer(id, secs)) }
        notifyDataSetChanged()
    }

    fun showNotes(notes: List<Note>) {
        items.clear()
        if (notes.isEmpty()) items.add(Item.Empty("No notes yet — ask me to save one!"))
        else notes.forEach { items.add(Item.NoteRow(it)) }
        notifyDataSetChanged()
    }

    fun showTodos(todos: List<TodoItem>) {
        items.clear()
        if (todos.isEmpty()) items.add(Item.Empty("No tasks yet — ask me to add one!"))
        else todos.forEach { items.add(Item.TodoRow(it)) }
        notifyDataSetChanged()
    }

    fun showAlarms(dummy: Any) {
        items.clear()
        items.add(Item.Empty("Alarms are set and will notify you — even when the app is closed."))
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(pos: Int) = when (items[pos]) {
        is Item.Timer -> 0; is Item.NoteRow -> 1; is Item.TodoRow -> 2; is Item.Empty -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (type) {
            0 -> TimerVH(com.offlinepal.databinding.ItemPanelTimerBinding.inflate(inf, parent, false))
            1 -> NoteVH(com.offlinepal.databinding.ItemPanelNoteBinding.inflate(inf, parent, false))
            2 -> TodoVH(com.offlinepal.databinding.ItemPanelTodoBinding.inflate(inf, parent, false))
            else -> EmptyVH(com.offlinepal.databinding.ItemPanelEmptyBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (val item = items[pos]) {
            is Item.Timer -> (holder as TimerVH).bind(item)
            is Item.NoteRow -> (holder as NoteVH).bind(item)
            is Item.TodoRow -> (holder as TodoVH).bind(item)
            is Item.Empty -> (holder as EmptyVH).bind(item)
        }
    }

    private fun fmt(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    inner class TimerVH(val b: com.offlinepal.databinding.ItemPanelTimerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Timer) { b.textTimerDisplay.text = fmt(item.secs); b.btnCancelTimer.setOnClickListener { onCancelTimer(item.id) } }
    }
    inner class NoteVH(val b: com.offlinepal.databinding.ItemPanelNoteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.NoteRow) { b.textNoteContent.text = item.note.content; b.btnDeleteNote.setOnClickListener { onDeleteNote(item.note) } }
    }
    inner class TodoVH(val b: com.offlinepal.databinding.ItemPanelTodoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.TodoRow) {
            b.checkboxTodo.isChecked = item.item.isCompleted
            b.textTodoTitle.text = item.item.title
            b.textPriority.text = item.item.category
            b.checkboxTodo.setOnClickListener { onToggleTodo(item.item) }
            b.btnDeleteTodo.setOnClickListener { onDeleteTodo(item.item) }
        }
    }
    inner class EmptyVH(val b: com.offlinepal.databinding.ItemPanelEmptyBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Empty) { b.textEmpty.text = item.msg }
    }
}
