package com.example.financialdashboard

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.*

// --- Data Models ---
data class BankAccount(
    val id: Long = System.nanoTime(),
    val name: String,
    val initialBalance: Double
)

data class Category(
    val id: Long = System.nanoTime(),
    val name: String,
    var amount: Double,
    val color: Long,
    val icon: String? // Storing icon name as string, now nullable
)

data class Expense(
    val id: Long = System.nanoTime(),
    val description: String,
    val amount: Double,
    val date: String, // YYYY-MM-DD
    val bankAccountId: Long,
    val categoryId: Long
)

data class MonthlyData(
    var monthlySalary: Double = 0.0,
    val categories: MutableList<Category> = mutableStateListOf(),
    val expenses: MutableList<Expense> = mutableStateListOf()
)

data class AppState(
    val bankAccounts: MutableList<BankAccount> = mutableStateListOf(),
    val monthlyData: MutableMap<String, MonthlyData> = mutableStateMapOf() // Key: YYYY-MM
)

// --- Icon Mapping ---
val iconMap = mapOf(
    "Savings" to Icons.Filled.Savings,
    "Investments" to Icons.Filled.TrendingUp,
    "Food" to Icons.Filled.Fastfood,
    "Transport" to Icons.Filled.Commute,
    "Shopping" to Icons.Filled.ShoppingCart,
    "Entertainment" to Icons.Filled.Movie,
    "Hotel" to Icons.Filled.Hotel,
    "Bills" to Icons.Filled.Receipt,
    "Default" to Icons.Filled.Label
)

fun getIcon(name: String?): ImageVector {
    return iconMap[name] ?: iconMap["Default"]!!
}


// --- ViewModel for State Management ---
@RequiresApi(Build.VERSION_CODES.O)
class FinancialViewModel : ViewModel() {
    var appState by mutableStateOf(AppState())
        private set

    var currentMonth by mutableStateOf(YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM")))
        private set

    var themeState by mutableStateOf<Boolean?>(null) // null = system, true = dark, false = light
        private set

    private val gson = Gson()

    fun onMonthSelected(yearMonth: String) {
        currentMonth = yearMonth
        getCurrentMonthData() // Ensure data exists for the selected month
    }

    fun setTheme(isDark: Boolean) {
        themeState = isDark
        App.prefs?.edit()?.putBoolean("theme_preference", isDark)?.apply()
    }

    private fun getCurrentMonthData(): MonthlyData {
        return appState.monthlyData.getOrPut(currentMonth) {
            MonthlyData(
                categories = mutableListOf(
                    Category(name = "Investments", amount = 0.0, color = 0xFF6366F1, icon = "Investments"),
                    Category(name = "Savings", amount = 0.0, color = 0xFF10B981, icon = "Savings"),
                    Category(name = "Food", amount = 0.0, color = 0xFFF59E0B, icon = "Food"),
                    Category(name = "Transport", amount = 0.0, color = 0xFF3B82F6, icon = "Transport"),
                    Category(name = "Hotel", amount = 0.0, color = 0xFFEC4899, icon = "Hotel")
                ).toMutableStateList()
            )
        }
    }
    fun getCategoryById(categoryId: Long): Category? {
        return appState.monthlyData.values
            .flatMap { it.categories }
            .firstOrNull { it.id == categoryId }
    }


    fun addBankAccount(name: String, initialBalance: Double) {
        appState.bankAccounts.add(BankAccount(name = name, initialBalance = initialBalance))
        saveData()
    }

    fun updateBankAccount(updatedAccount: BankAccount) {
        val index = appState.bankAccounts.indexOfFirst { it.id == updatedAccount.id }
        if (index != -1) {
            appState.bankAccounts[index] = updatedAccount
            saveData()
        }
    }

    fun deleteBankAccount(accountId: Long) {
        appState.bankAccounts.removeAll { it.id == accountId }
        appState.monthlyData.values.forEach { monthData ->
            monthData.expenses.removeAll { it.bankAccountId == accountId }
        }
        saveData()
    }

    fun updateSalary(salary: Double) {
        getCurrentMonthData().monthlySalary = salary
        saveData()
    }

    fun addCategory(name: String, icon: String) {
        val colors = listOf(0xFF8b5cf6, 0xFFec4899, 0xFFf59e0b, 0xFF64748b, 0xFFef4444)
        val existingCategories = getCurrentMonthData().categories.size
        val newCategory = Category(
            name = name,
            amount = 0.0,
            color = colors[existingCategories % colors.size],
            icon = icon
        )
        getCurrentMonthData().categories.add(newCategory)
        saveData()
    }
    fun updateCategory(updatedCategory: Category) {
        val monthData = getCurrentMonthData()
        val index = monthData.categories.indexOfFirst { it.id == updatedCategory.id }
        if (index != -1) {
            monthData.categories[index] = updatedCategory
            appState = appState.copy(monthlyData = appState.monthlyData.toMutableMap())
            saveData()
        }
    }

    fun deleteCategory(categoryId: Long) {
        getCurrentMonthData().categories.removeAll { it.id == categoryId }
        saveData()
    }

    fun addExpense(description: String, amount: Double, date: String, bankAccountId: Long) {
        val yearMonth = date.substring(0, 7)
        val monthData = appState.monthlyData.getOrPut(yearMonth) { getCurrentMonthData() }

        var dailySpendsCategory = monthData.categories.find { it.name.equals("Daily Spends", ignoreCase = true) }
        if (dailySpendsCategory == null) {
            dailySpendsCategory = Category(name = "Daily Spends", amount = 0.0, color = 0xFF9CA3AF, icon = "Default")
            monthData.categories.add(dailySpendsCategory)
        }

        monthData.expenses.add(0, Expense(description = description, amount = amount, date = date, bankAccountId = bankAccountId, categoryId = dailySpendsCategory.id))
        appState = appState.copy(monthlyData = appState.monthlyData.toMutableMap()) // Trigger recomposition
        saveData()
    }

    fun deleteExpense(expenseId: Long) {
        appState.monthlyData.values.forEach { monthData ->
            monthData.expenses.removeAll { it.id == expenseId }
        }
        saveData()
    }

    private fun saveData() {
        val stateToSave = appState.copy(
            bankAccounts = appState.bankAccounts.toMutableList(),
            monthlyData = appState.monthlyData.toMutableMap()
        )
        val json = gson.toJson(stateToSave)
        App.prefs?.edit()?.putString("app_state", json)?.apply()
    }

    fun loadData() {
        if (App.prefs?.contains("theme_preference") == true) {
            themeState = App.prefs?.getBoolean("theme_preference", false)
        }

        val json = App.prefs?.getString("app_state", null)
        if (json != null) {
            val type = object : TypeToken<AppState>() {}.type
            try {
                val loadedState: AppState = gson.fromJson(json, type)
                val observableMonthlyData = loadedState.monthlyData.mapValues { entry ->
                    val monthData = entry.value
                    // Data migration to fix potential crashes from old data missing an icon.
                    val fixedCategories = monthData.categories.map { category ->
                        if (category.icon == null) {
                            category.copy(icon = "Default")
                        } else {
                            category
                        }
                    }.toMutableStateList()

                    monthData.copy(
                        categories = fixedCategories,
                        expenses = monthData.expenses.toMutableStateList()
                    )
                }
                appState = AppState(
                    bankAccounts = loadedState.bankAccounts.toMutableStateList(),
                    monthlyData = observableMonthlyData.toList().toMutableStateMap()
                )
            } catch (e: Exception) {
                Log.e("FinancialViewModel", "Error loading or parsing data", e)
                appState = AppState()
            }
        }
        getCurrentMonthData()
    }
}

object App {
    var prefs: android.content.SharedPreferences? = null
}

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.prefs = getSharedPreferences("financial_dashboard_prefs", MODE_PRIVATE)
        setContent {
            FinancialDashboardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FinancialDashboardApp()
                }
            }
        }
    }
}

// --- Main App Composable ---
@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun FinancialDashboardApp() {
    val viewModel: FinancialViewModel = viewModel()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Entries", "Budget")

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    val systemIsDark = isSystemInDarkTheme()
    val isDarkTheme = viewModel.themeState ?: systemIsDark

    FinancialDashboardTheme(darkTheme = isDarkTheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawerContent(
                    viewModel = viewModel,
                    onMonthSelected = { month ->
                        viewModel.onMonthSelected(month)
                        scope.launch { drawerState.close() }
                    },
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = { viewModel.setTheme(!isDarkTheme) }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Zenith Finance", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open Drawer")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                    ) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        when (index) {
                                            0 -> Icons.Filled.Article
                                            else -> Icons.Filled.DonutSmall
                                        },
                                        contentDescription = title
                                    )
                                },
                                label = { Text(title) },
                                selected = selectedTab == index,
                                onClick = { selectedTab = index }
                            )
                        }
                    }
                }
            ) { paddingValues ->
                Crossfade(
                    targetState = selectedTab,
                    modifier = Modifier.padding(paddingValues),
                    animationSpec = tween(300)
                ) { screen ->
                    when (screen) {
                        0 -> TransactionsScreen(viewModel)
                        1 -> BudgetScreen(viewModel)
                    }
                }
            }
        }
    }
}

// --- Drawer Composable ---
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerContent(
    viewModel: FinancialViewModel,
    onMonthSelected: (String) -> Unit,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit
) {
    val months = viewModel.appState.monthlyData.keys.sortedDescending()
    val currentMonth = viewModel.currentMonth
    val totalInitialBalance = viewModel.appState.bankAccounts.sumOf { it.initialBalance }
    val totalExpenses = viewModel.appState.monthlyData.values.sumOf { monthData -> monthData.expenses.sumOf { it.amount } }
    val totalAllocated = viewModel.appState.monthlyData.values.sumOf { monthData -> monthData.categories.sumOf { it.amount } }
    val currentTotalBalance = totalInitialBalance - totalExpenses - totalAllocated

    val allMonthlyData = viewModel.appState.monthlyData.values
    val totalInvestments = allMonthlyData.sumOf { monthData ->
        monthData.categories.find { it.name.equals("Investments", ignoreCase = true) }?.amount ?: 0.0
    }
    val totalSavings = allMonthlyData.sumOf { monthData ->
        monthData.categories.find { it.name.equals("Savings", ignoreCase = true) }?.amount ?: 0.0
    }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Section for Total Balance
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp))
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AccountBalanceWallet,
                        contentDescription = "Current Balance",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Current Balance",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                AnimatedCounter(target = currentTotalBalance)
                Spacer(Modifier.height(16.dp))

                // Lifetime Stats
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.TrendingUp,
                        contentDescription = "Total Investments",
                        tint = Color(0xFF6366F1),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Investments",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    AnimatedCounter(target = totalInvestments, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Savings,
                        contentDescription = "Total Savings",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Savings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    AnimatedCounter(target = totalSavings, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }

            // History Section
            Text(
                "Monthly History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)
            )

            // List of months
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                if (months.isEmpty()) {
                    item {
                        Text(
                            "No history yet.",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(months, key = { "month_$it" }) { month ->
                        val isSelected = month == currentMonth
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = YearMonth.parse(month).format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            selected = isSelected,
                            onClick = { onMonthSelected(month) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                selectedTextColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            // Theme Toggle
            Row(
                modifier = Modifier
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Dark Mode",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = { onThemeToggle() }
                )
            }


            // Footer
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Text(
                "Zenith Finance v1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }
}


// --- Screen Composables ---
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun BudgetScreen(viewModel: FinancialViewModel) {
    val monthData = viewModel.appState.monthlyData[viewModel.currentMonth] ?: MonthlyData()
    var salaryText by remember(viewModel.currentMonth, monthData.monthlySalary) { mutableStateOf(monthData.monthlySalary.takeIf { it > 0 }?.toString() ?: "") }
    var showMonthPicker by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showBankAccountDialog by remember { mutableStateOf(false) }
    var bankAccountToEdit by remember { mutableStateOf<BankAccount?>(null) }


    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { showAddCategoryDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Category")
                }
                FloatingActionButton(
                    onClick = {
                        bankAccountToEdit = null
                        showBankAccountDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.AccountBalance, contentDescription = "Add Bank Account")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(padding)
        ) {
            item {
                OutlinedTextField(
                    value = YearMonth.parse(viewModel.currentMonth).format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    onValueChange = { },
                    label = { Text("Selected Month") },
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Filled.CalendarToday, null) },
                    modifier = Modifier.fillMaxWidth().clickable { showMonthPicker = true },
                    shape = RoundedCornerShape(12.dp)
                )
            }

            item {
                OutlinedTextField(
                    value = salaryText,
                    onValueChange = {
                        salaryText = it
                        viewModel.updateSalary(it.toDoubleOrNull() ?: 0.0)
                    },
                    label = { Text("Your Monthly Salary") },
                    leadingIcon = { Icon(Icons.Filled.AttachMoney, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            item { AllocationBar(monthData) }

            item { Text("Budget Categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }

            itemsIndexed(monthData.categories.filterNot { it.name.equals("Daily Spends", true) }, key = { _, category -> "category_${category.id}" }) { index, category ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(delayMillis = index * 100))
                            + slideInVertically(animationSpec = tween(delayMillis = index * 100),
                        initialOffsetY = { it / 2 })
                ) {
                    CategoryCard(
                        category = category,
                        onAmountChange = { newAmount ->
                            viewModel.updateCategory(category.copy(amount = newAmount))
                        },
                        onDelete = { viewModel.deleteCategory(category.id) }
                    )
                }
            }
            if (viewModel.appState.bankAccounts.isNotEmpty()) {
                item { Spacer(Modifier.height(16.dp)) }
                item { Text("Bank Accounts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }

                items(viewModel.appState.bankAccounts, key = { account -> "account_${account.id}" }) { account ->
                    BankAccountCard(
                        account = account,
                        onEditClick = {
                            bankAccountToEdit = account
                            showBankAccountDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showAddCategoryDialog) {
        AddCategoryDialog(
            onDismiss = { showAddCategoryDialog = false },
            onConfirm = { name, icon ->
                viewModel.addCategory(name, icon)
                showAddCategoryDialog = false
            }
        )
    }

    if (showBankAccountDialog) {
        BankAccountDialog(
            accountToEdit = bankAccountToEdit,
            onDismiss = { showBankAccountDialog = false },
            onConfirm = { name, balance, id ->
                if (id == null) { // Adding new
                    viewModel.addBankAccount(name, balance)
                } else { // Editing existing
                    viewModel.updateBankAccount(BankAccount(id = id, name = name, initialBalance = balance))
                }
                showBankAccountDialog = false
            }
        )
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            onDismiss = { showMonthPicker = false },
            onMonthSelected = { yearMonth ->
                viewModel.onMonthSelected(yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                showMonthPicker = false
            }
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TransactionsScreen(viewModel: FinancialViewModel) {
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    val currentMonthData = viewModel.appState.monthlyData[viewModel.currentMonth] ?: MonthlyData()
    val expensesForCurrentMonth = currentMonthData.expenses
    val expensesByDate = expensesForCurrentMonth.groupBy { it.date }
        .toSortedMap(compareByDescending { LocalDate.parse(it) })

    val totalExpense = expensesForCurrentMonth.sumOf { it.amount }
    val salary = currentMonthData.monthlySalary
    val totalAllocated = currentMonthData.categories.sumOf { it.amount }
    val remainingAmount = salary - totalAllocated - totalExpense


    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddExpenseDialog = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, "Add Expense")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Header with Total and Daily Average
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Total Expense", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedCounter(target = totalExpense, style = MaterialTheme.typography.headlineSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Remaining Amount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedCounter(target = remainingAmount, style = MaterialTheme.typography.headlineSmall)
                }
            }

            // Expense List
            if (expensesForCurrentMonth.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No expenses this month. Tap '+' to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    expensesByDate.forEach { (date, expenses) ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    formatDate(date, "EEEE, d'th' MMMM"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    formatCurrency(expenses.sumOf { it.amount }),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(expenses, key = { it.id }) { expense ->
                            AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically()) {
                                ExpenseItem(
                                    expense = expense,
                                    category = viewModel.getCategoryById(expense.categoryId),
                                    onDelete = { viewModel.deleteExpense(expense.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddExpenseDialog) {
        AddExpenseDialog(
            bankAccounts = viewModel.appState.bankAccounts,
            onDismiss = { showAddExpenseDialog = false },
            onConfirm = { desc, amount, date, bankId ->
                viewModel.addExpense(desc, amount, date, bankId)
                showAddExpenseDialog = false
            }
        )
    }
}

// --- Reusable UI Components ---

@Composable
fun AnimatedCounter(
    target: Double,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall,
    fontWeight: FontWeight = FontWeight.ExtraBold,
) {
    val animatedValue by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = tween(durationMillis = 1000)
    )

    Text(
        text = formatCurrency(animatedValue.toDouble()),
        style = style,
        fontWeight = fontWeight,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun AllocationBar(monthData: MonthlyData) {
    val totalAllocated = monthData.categories.sumOf { it.amount }
    if (monthData.monthlySalary > 0 && totalAllocated > 0) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                horizontalArrangement = Arrangement.Start
            ) {
                monthData.categories.forEach { category ->
                    if (category.amount > 0) {
                        val percentage = (category.amount / monthData.monthlySalary).toFloat()
                        val animatedWeight by animateFloatAsState(
                            targetValue = percentage,
                            animationSpec = tween(durationMillis = 1000)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(animatedWeight)
                                .background(Color(category.color))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryCard(category: Category, onAmountChange: (Double) -> Unit, onDelete: () -> Unit) {
    var amountText by remember(category.amount) { mutableStateOf(category.amount.takeIf { it > 0 }?.toString() ?: "") }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = getIcon(category.icon),
                        contentDescription = category.name,
                        tint = Color(category.color)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(category.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete category", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    onAmountChange(it.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Allocated Amount")},
                leadingIcon = { Icon(Icons.Filled.AttachMoney, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ExpenseItem(expense: Expense, category: Category?, onDelete: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(category?.let { Color(it.color) } ?: MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = category?.let { getIcon(it.icon) } ?: getIcon("Default"),
                contentDescription = category?.name,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(expense.description, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            category?.name?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(formatCurrency(expense.amount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { showDeleteDialog = true }) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "Delete Expense",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Confirm Deletion") },
            text = { Text("Are you sure you want to delete this expense: '${expense.description}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BankAccountCard(account: BankAccount, onEditClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AccountBalance,
                contentDescription = "Bank Account",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(formatCurrency(account.initialBalance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEditClick) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit Account", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// --- Dialogs ---
@Composable
fun BankAccountDialog(
    accountToEdit: BankAccount?,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Long?) -> Unit
) {
    var name by remember { mutableStateOf(accountToEdit?.name ?: "") }
    var balance by remember { mutableStateOf(accountToEdit?.initialBalance?.toString() ?: "") }

    val title = if (accountToEdit == null) "Add New Bank Account" else "Edit Bank Account"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name") },
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { if (accountToEdit == null) "Initial Balance" else "Update Balance" },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, balance.toDoubleOrNull() ?: 0.0, accountToEdit?.id) },
                enabled = name.isNotBlank() && balance.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCategoryDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf(iconMap.keys.first()) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Add New Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedIcon,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Icon")},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        iconMap.keys.forEach { iconName ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = getIcon(iconName), contentDescription = iconName)
                                        Spacer(Modifier.width(8.dp))
                                        Text(iconName)
                                    }
                                },
                                onClick = {
                                    selectedIcon = iconName
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, selectedIcon) }, enabled = name.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseDialog(
    bankAccounts: List<BankAccount>,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, String, Long) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var selectedBankAccountId by remember { mutableStateOf(bankAccounts.firstOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Add New Expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))

                var bankExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = bankExpanded, onExpandedChange = { bankExpanded = !bankExpanded }) {
                    OutlinedTextField(
                        value = bankAccounts.find { it.id == selectedBankAccountId }?.name ?: "Select Account",
                        onValueChange = {}, readOnly = true, label = { Text("Account")},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bankExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = bankExpanded, onDismissRequest = { bankExpanded = false }) {
                        bankAccounts.forEach { account ->
                            DropdownMenuItem(text = { Text(account.name) }, onClick = { selectedBankAccountId = account.id; bankExpanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val expenseAmount = amount.toDoubleOrNull()
                    if (description.isNotBlank() && expenseAmount != null && selectedBankAccountId != null) {
                        onConfirm(description, expenseAmount, LocalDate.now().toString(), selectedBankAccountId!!)
                    }
                },
                enabled = description.isNotBlank() && amount.toDoubleOrNull() != null && selectedBankAccountId != null
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MonthPickerDialog(onDismiss: () -> Unit, onMonthSelected: (YearMonth) -> Unit) {
    val currentMonth = YearMonth.now()
    val months = (0..12).map { currentMonth.minusMonths(it.toLong()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
                items(months) { month ->
                    Text(
                        text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        modifier = Modifier.fillMaxWidth().clickable { onMonthSelected(month) }.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}


// --- Utility Functions ---
fun formatCurrency(amount: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    return format.format(amount)
}

@RequiresApi(Build.VERSION_CODES.O)
fun formatDate(dateString: String, pattern: String = "MMM dd, yyyy"): String {
    return try {
        val date = LocalDate.parse(dateString)
        date.format(DateTimeFormatter.ofPattern(pattern))
    } catch (e: Exception) {
        dateString
    }
}

// --- Theme ---
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFEF4444), // Red Accent
    onPrimary = Color.White,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFBDBDBD),
    error = Color(0xFFF472B6),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFEF4444), // Red Accent
    onPrimary = Color.White,
    background = Color(0xFFF9FAFB),
    surface = Color.White,
    onSurface = Color(0xFF1F2937),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    error = Color(0xFFDC2626),
    onError = Color.White
)


@Composable
fun FinancialDashboardTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // Typography can be further customized here
        content = content
    )
}

