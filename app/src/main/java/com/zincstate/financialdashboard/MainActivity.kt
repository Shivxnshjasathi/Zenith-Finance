package com.zincstate.financialdashboard

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.*

// --- Data Models ---
// Note: Added empty constructors and default values for Firestore serialization
data class BankAccount(
    val id: Long = System.nanoTime(),
    val name: String = "",
    val initialBalance: Double = 0.0
)

data class Category(
    val id: Long = System.nanoTime(),
    val name: String = "",
    var amount: Double = 0.0,
    val color: Long = 0L,
    val icon: String? = "Default"
)

data class Expense(
    val id: Long = System.nanoTime(),
    val description: String = "",
    val amount: Double = 0.0,
    val date: String = "", // YYYY-MM-DD
    val bankAccountId: Long = 0L,
    val categoryId: Long = 0L
)

data class MonthlyData(
    var monthlySalary: Double = 0.0,
    val categories: List<Category> = listOf(),
    val expenses: List<Expense> = listOf()
)

data class AppState(
    val bankAccounts: List<BankAccount> = listOf(),
    val monthlyData: Map<String, MonthlyData> = mapOf() // Key: YYYY-MM
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

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating

    private val auth: FirebaseAuth = Firebase.auth
    private val db: FirebaseFirestore = Firebase.firestore

    init {
        // If user is already logged in when ViewModel is created (e.g., on app launch), load their data.
        if (auth.currentUser != null) {
            loadData()
        } else {
            // If not logged in, we are not loading, so set loading to false.
            _isLoading.value = false
        }
    }

    fun onMonthSelected(yearMonth: String) {
        currentMonth = yearMonth
        getCurrentMonthData() // Ensure data exists for the selected month
    }

    fun setTheme(isDark: Boolean) {
        themeState = isDark
        App.prefs?.edit()?.putBoolean("theme_preference", isDark)?.apply()
    }

    private fun getCurrentMonthData(): MonthlyData {
        return appState.monthlyData[currentMonth] ?: MonthlyData(
            categories = listOf(
                Category(name = "Investments", amount = 0.0, color = 0xFF6366F1, icon = "Investments"),
                Category(name = "Savings", amount = 0.0, color = 0xFF10B981, icon = "Savings"),
                Category(name = "Food", amount = 0.0, color = 0xFFF59E0B, icon = "Food"),
                Category(name = "Transport", amount = 0.0, color = 0xFF3B82F6, icon = "Transport"),
                Category(name = "Hotel", amount = 0.0, color = 0xFFEC4899, icon = "Hotel")
            )
        )
    }

    fun getCategoryById(categoryId: Long): Category? {
        return appState.monthlyData.values
            .flatMap { it.categories }
            .firstOrNull { it.id == categoryId }
    }

    fun getBankAccountCurrentBalance(bankAccount: BankAccount): Double {
        val totalExpensesForAccount = appState.monthlyData.values
            .flatMap { it.expenses }
            .filter { it.bankAccountId == bankAccount.id }
            .sumOf { it.amount }
        return bankAccount.initialBalance - totalExpensesForAccount
    }

    fun addBankAccount(name: String, initialBalance: Double) {
        val newAccount = BankAccount(name = name, initialBalance = initialBalance)
        appState = appState.copy(bankAccounts = appState.bankAccounts + newAccount)
        saveData()
    }

    fun updateBankAccount(updatedAccount: BankAccount) {
        val updatedAccounts = appState.bankAccounts.map { if (it.id == updatedAccount.id) updatedAccount else it }
        appState = appState.copy(bankAccounts = updatedAccounts)
        saveData()
    }

    fun deleteBankAccount(accountId: Long) {
        val updatedAccounts = appState.bankAccounts.filter { it.id != accountId }
        val updatedMonthlyData = appState.monthlyData.mapValues { entry ->
            entry.value.copy(expenses = entry.value.expenses.filter { it.bankAccountId != accountId })
        }
        appState = appState.copy(bankAccounts = updatedAccounts, monthlyData = updatedMonthlyData)
        saveData()
    }

    fun updateSalary(salary: Double) {
        val monthData = getCurrentMonthData().copy(monthlySalary = salary)
        val updatedMonthlyData = appState.monthlyData.toMutableMap()
        updatedMonthlyData[currentMonth] = monthData
        appState = appState.copy(monthlyData = updatedMonthlyData)
        saveData()
    }

    fun addCategory(name: String, icon: String) {
        val colors = listOf(0xFF8b5cf6, 0xFFec4899, 0xFFf59e0b, 0xFF64748b, 0xFFef4444)
        val monthData = getCurrentMonthData()
        val existingCategories = monthData.categories.size
        val newCategory = Category(
            name = name,
            amount = 0.0,
            color = colors[existingCategories % colors.size],
            icon = icon
        )
        val updatedCategories = monthData.categories + newCategory
        val updatedMonthlyData = appState.monthlyData.toMutableMap()
        updatedMonthlyData[currentMonth] = monthData.copy(categories = updatedCategories)
        appState = appState.copy(monthlyData = updatedMonthlyData)
        saveData()
    }

    fun updateCategory(updatedCategory: Category) {
        val monthData = getCurrentMonthData()
        val updatedCategories = monthData.categories.map { if (it.id == updatedCategory.id) updatedCategory else it }
        val updatedMonthlyData = appState.monthlyData.toMutableMap()
        updatedMonthlyData[currentMonth] = monthData.copy(categories = updatedCategories)
        appState = appState.copy(monthlyData = updatedMonthlyData)
        saveData()
    }

    fun deleteCategory(categoryId: Long) {
        val monthData = getCurrentMonthData()
        val updatedCategories = monthData.categories.filter { it.id != categoryId }
        val updatedMonthlyData = appState.monthlyData.toMutableMap()
        updatedMonthlyData[currentMonth] = monthData.copy(categories = updatedCategories)
        appState = appState.copy(monthlyData = updatedMonthlyData)
        saveData()
    }

    fun addExpense(description: String, amount: Double, date: String, bankAccountId: Long) {
        val yearMonth = date.substring(0, 7)
        val monthData = appState.monthlyData[yearMonth] ?: MonthlyData()

        var updatedCategories = monthData.categories
        var dailySpendsCategory = updatedCategories.find { it.name.equals("Daily Spends", ignoreCase = true) }
        if (dailySpendsCategory == null) {
            dailySpendsCategory = Category(name = "Daily Spends", amount = 0.0, color = 0xFF9CA3AF, icon = "Default")
            updatedCategories = updatedCategories + dailySpendsCategory
        }

        val newExpense = Expense(description = description, amount = amount, date = date, bankAccountId = bankAccountId, categoryId = dailySpendsCategory.id)
        val updatedExpenses = listOf(newExpense) + monthData.expenses

        val updatedMonthData = monthData.copy(categories = updatedCategories, expenses = updatedExpenses)
        val updatedMonthlyDataMap = appState.monthlyData.toMutableMap()
        updatedMonthlyDataMap[yearMonth] = updatedMonthData
        appState = appState.copy(monthlyData = updatedMonthlyDataMap)
        saveData()
    }

    fun updateExpense(updatedExpense: Expense) {
        val yearMonth = updatedExpense.date.substring(0, 7)
        val monthData = appState.monthlyData[yearMonth] ?: return

        val updatedExpenses = monthData.expenses.map { if (it.id == updatedExpense.id) updatedExpense else it }
        val updatedMonthlyDataMap = appState.monthlyData.toMutableMap()
        updatedMonthlyDataMap[yearMonth] = monthData.copy(expenses = updatedExpenses)
        appState = appState.copy(monthlyData = updatedMonthlyDataMap)
        saveData()
    }

    fun deleteExpense(expenseId: Long) {
        val updatedMonthlyData = appState.monthlyData.mapValues { entry ->
            entry.value.copy(expenses = entry.value.expenses.filter { it.id != expenseId })
        }
        appState = appState.copy(monthlyData = updatedMonthlyData)
        saveData()
    }

    // --- Firebase Methods ---
    private fun saveData() {
        auth.currentUser?.uid?.let { userId ->
            db.collection("users").document(userId).set(appState)
                .addOnFailureListener { e -> Log.w("ViewModel", "Error writing document", e) }
        }
    }

    fun loadData() {
        _isLoading.value = true
        auth.currentUser?.uid?.let { userId ->
            if (App.prefs?.contains("theme_preference") == true) {
                themeState = App.prefs?.getBoolean("theme_preference", false)
            }
            db.collection("users").document(userId).addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("ViewModel", "Listen failed.", e)
                    _isLoading.value = false
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    appState = snapshot.toObject<AppState>() ?: AppState()
                } else {
                    appState = AppState() // No data yet, start with fresh state
                }
                getCurrentMonthData() // Ensure current month data exists
                _isLoading.value = false
            }
        } ?: run { _isLoading.value = false }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _isAuthenticating.value = true
            try {
                auth.createUserWithEmailAndPassword(email, password).await()
                _authError.value = null
                loadData()
            } catch (e: Exception) {
                _authError.value = e.localizedMessage
            } finally {
                _isAuthenticating.value = false
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _isAuthenticating.value = true
            try {
                auth.signInWithEmailAndPassword(email, password).await()
                _authError.value = null
                loadData()
            } catch (e: Exception) {
                _authError.value = e.localizedMessage
            } finally {
                _isAuthenticating.value = false
            }
        }
    }

    fun signOut() {
        auth.signOut()
        appState = AppState() // Clear state on sign out
    }

    fun clearAuthError() {
        _authError.value = null
    }
}

object App {
    var prefs: android.content.SharedPreferences? = null
}

class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.prefs = getSharedPreferences("financial_dashboard_prefs", MODE_PRIVATE)
        auth = Firebase.auth

        setContent {
            val viewModel: FinancialViewModel = viewModel()
            val systemIsDark = isSystemInDarkTheme()
            val isDarkTheme = viewModel.themeState ?: systemIsDark

            FinancialDashboardTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel, auth)
                }
            }
        }
    }
}

// --- App Navigation ---
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppNavigation(viewModel: FinancialViewModel, auth: FirebaseAuth) {
    var isLoggedIn by remember { mutableStateOf(auth.currentUser != null) }
    val isLoading by viewModel.isLoading.collectAsState()
    var hasSeenOnboarding by remember {
        mutableStateOf(App.prefs?.getBoolean("has_seen_onboarding", false) ?: false)
    }

    LaunchedEffect(auth) {
        auth.addAuthStateListener { firebaseAuth ->
            val userLoggedIn = firebaseAuth.currentUser != null
            if (userLoggedIn && !isLoggedIn) { // User just logged in
                viewModel.loadData()
            }
            isLoggedIn = userLoggedIn
        }
    }

    if (isLoading && isLoggedIn) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (!hasSeenOnboarding) {
        OnboardingScreen(onOnboardingFinished = {
            App.prefs?.edit()?.putBoolean("has_seen_onboarding", true)?.apply()
            hasSeenOnboarding = true
        })
    } else if (isLoggedIn) {
        FinancialDashboardApp()
    } else {
        AuthScreen(viewModel)
    }
}


// --- Onboarding Screen ---
data class OnboardingPageData(
    val icon: ImageVector,
    val title: String,
    val description: String
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onOnboardingFinished: () -> Unit) {
    val pages = listOf(
        OnboardingPageData(
            icon = Icons.Outlined.TrackChanges,
            title = "Track Your Spending",
            description = "Easily record your daily expenses and see where your money is going."
        ),
        OnboardingPageData(
            icon = Icons.Outlined.PieChart,
            title = "Set Your Budgets",
            description = "Create monthly budgets for different categories to stay on top of your finances."
        ),
        OnboardingPageData(
            icon = Icons.Outlined.AccountBalanceWallet,
            title = "Manage All Accounts",
            description = "Get a clear view of all your bank accounts and balances in one place."
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                OnboardingPage(pageData = pages[page])
            }

            Row(
                Modifier
                    .wrapContentHeight()
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(12.dp)
                    )
                }
            }

            Button(
                onClick = {
                    if (pagerState.currentPage < pages.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onOnboardingFinished()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (pagerState.currentPage < pages.size - 1) "Next" else "Get Started")
            }
        }
    }
}

@Composable
fun OnboardingPage(pageData: OnboardingPageData) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = pageData.icon,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(48.dp))
        Text(
            text = pageData.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = pageData.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


// --- Authentication Screen ---
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(viewModel: FinancialViewModel) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val authError by viewModel.authError.collectAsState()
    val isAuthenticating by viewModel.isAuthenticating.collectAsState()
    val tabs = listOf("Log In", "Sign Up")

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.5f))

            // Logo and Title Section
            Icon(
                imageVector = Icons.Filled.Savings, // Placeholder for the logo
                contentDescription = "App Logo",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Ready to Begin?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Join us or log in to explore the features of our app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

            // TabRow for Log In / Sign Up
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        height = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = {
                            selectedTabIndex = index
                            viewModel.clearAuthError()
                        },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Animate content based on tab selection
            Crossfade(targetState = selectedTabIndex, animationSpec = tween(300)) { tabIndex ->
                when (tabIndex) {
                    0 -> LoginContent(viewModel, isAuthenticating)
                    1 -> SignUpContent(viewModel, isAuthenticating)
                }
            }

            authError?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.weight(1f))

            // Social Login Section
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Divider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    Text(
                        " Or login with ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Divider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    // Placeholder Social Buttons
                    SocialLoginButton(icon = Icons.Default.Computer, onClick = {}) // Apple placeholder
                    SocialLoginButton(icon = Icons.Default.AccountCircle, onClick = {}) // Google placeholder
                    SocialLoginButton(icon = Icons.Default.Message, onClick = {}) // Facebook placeholder
                    SocialLoginButton(icon = Icons.Default.Phone, onClick = {}) // Phone placeholder
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SocialLoginButton(icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(54.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(imageVector = icon, contentDescription = "Social Login", modifier = Modifier.size(24.dp))
    }
}


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LoginContent(viewModel: FinancialViewModel, isAuthenticating: Boolean) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = { rememberMe = it }
                )
                Text("Remember me", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { /* TODO: Implement Forgot Password */ }) {
                Text("Forgot Password?")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.signIn(email, password) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = email.isNotBlank() && password.isNotBlank() && !isAuthenticating,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isAuthenticating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text("Log In")
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SignUpContent(viewModel: FinancialViewModel, isAuthenticating: Boolean) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (min. 6 characters)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { viewModel.signUp(email, password) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = email.isNotBlank() && password.length >= 6 && !isAuthenticating,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isAuthenticating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text("Sign Up")
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
                    onThemeToggle = { viewModel.setTheme(!isDarkTheme) },
                    onSignOut = {
                        scope.launch { drawerState.close() }
                        viewModel.signOut()
                    }
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
    onThemeToggle: () -> Unit,
    onSignOut: () -> Unit
) {
    val months = viewModel.appState.monthlyData.keys.sortedDescending()
    val currentMonth = viewModel.currentMonth
    val totalInitialBalance = viewModel.appState.bankAccounts.sumOf { it.initialBalance }
    val totalExpenses = viewModel.appState.monthlyData.values.sumOf { monthData -> monthData.expenses.sumOf { it.amount } }
    val currentTotalBalance = totalInitialBalance - totalExpenses

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

            Text(
                "Monthly History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)
            )

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

            Divider()

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
            NavigationDrawerItem(
                label = { Text("Sign Out") },
                icon = { Icon(Icons.Default.Logout, contentDescription = "Sign Out")},
                selected = false,
                onClick = onSignOut,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            )

            Text(
                "Zenith Finance v1.4",
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
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            item {
                OutlinedTextField(
                    value = YearMonth.parse(viewModel.currentMonth).format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    onValueChange = { },
                    label = { Text("Selected Month") },
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Filled.CalendarToday, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMonthPicker = true },
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
                    val currentBalance = viewModel.getBankAccountCurrentBalance(account)
                    BankAccountCard(
                        account = account,
                        currentBalance = currentBalance,
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
            onDismiss = { showAddCategoryDialog = false },
            onConfirm = { name, balance, id ->
                if (id == null) {
                    viewModel.addBankAccount(name, balance)
                } else {
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
    var expenseToEdit by remember { mutableStateOf<Expense?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val currentMonthData = viewModel.appState.monthlyData[viewModel.currentMonth] ?: MonthlyData()

    val filteredExpenses = currentMonthData.expenses.filter {
        it.description.contains(searchQuery, ignoreCase = true)
    }

    val expensesByDate = filteredExpenses.groupBy { it.date }
        .toSortedMap(compareByDescending { LocalDate.parse(it) })

    val totalExpense = currentMonthData.expenses.sumOf { it.amount }
    val salary = currentMonthData.monthlySalary
    val totalAllocated = currentMonthData.categories.sumOf { it.amount }
    val remainingAmount = salary - totalAllocated - totalExpense


    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    expenseToEdit = null
                    showAddExpenseDialog = true
                },
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AnimatedCounter(target = totalExpense, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Remaining", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AnimatedCounter(target = remainingAmount, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search transactions...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            )

            if (currentMonthData.expenses.isEmpty()) {
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
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    expensesByDate.forEach { (date, expenses) ->
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
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
                                    onDelete = { viewModel.deleteExpense(expense.id) },
                                    onEdit = {
                                        expenseToEdit = expense
                                        showAddExpenseDialog = true
                                    }
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
            expenseToEdit = expenseToEdit,
            bankAccounts = viewModel.appState.bankAccounts,
            onDismiss = { showAddExpenseDialog = false },
            onConfirm = { desc, amount, date, bankId, id ->
                if (id == null) {
                    viewModel.addExpense(desc, amount, date, bankId)
                } else {
                    expenseToEdit?.let { originalExpense ->
                        viewModel.updateExpense(originalExpense.copy(
                            description = desc,
                            amount = amount,
                            date = date,
                            bankAccountId = bankId
                        ))
                    }
                }
                showAddExpenseDialog = false
            }
        )
    }
}


// --- Reusable UI Components and Dialogs ---
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
fun ExpenseItem(expense: Expense, category: Category?, onDelete: () -> Unit, onEdit: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
        Text(formatCurrency(expense.amount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit Expense", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        IconButton(onClick = { showDeleteDialog = true }) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Expense", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
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
fun BankAccountCard(account: BankAccount, currentBalance: Double, onEditClick: () -> Unit) {
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
                Text(formatCurrency(currentBalance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    expenseToEdit: Expense?,
    bankAccounts: List<BankAccount>,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, String, Long, Long?) -> Unit
) {
    var description by remember { mutableStateOf(expenseToEdit?.description ?: "") }
    var amount by remember { mutableStateOf(expenseToEdit?.amount?.toString() ?: "") }
    var selectedBankAccountId by remember { mutableStateOf(expenseToEdit?.bankAccountId ?: bankAccounts.firstOrNull()?.id) }
    val isEditing = expenseToEdit != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (isEditing) "Edit Expense" else "Add New Expense") },
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
                        onConfirm(description, expenseAmount, expenseToEdit?.date ?: LocalDate.now().toString(), selectedBankAccountId!!, expenseToEdit?.id)
                    }
                },
                enabled = description.isNotBlank() && amount.toDoubleOrNull() != null && selectedBankAccountId != null
            ) { Text(if (isEditing) "Save" else "Add") }
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
    primary = Color(0xFF5891F8), // Lighter Blue for Dark Mode
    onPrimary = Color.Black,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFBDBDBD),
    error = Color(0xFFF472B6),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4F81F7), // Strong Blue for Light Mode
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

