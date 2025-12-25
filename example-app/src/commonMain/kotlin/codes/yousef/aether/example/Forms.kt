package codes.yousef.aether.example

import codes.yousef.aether.forms.CharField
import codes.yousef.aether.forms.Form
import codes.yousef.aether.forms.PasswordField
import codes.yousef.aether.forms.ValidationError

class LoginForm : Form() {
    val username = register("username", CharField("Username"))
    val password = register("password", PasswordField("Password"))
}

class RegisterForm : Form() {
    val username = register("username", CharField("Username"))
    val email = register("email", CharField("Email"))
    val password = register("password", PasswordField("Password"))
    val confirmPassword = register("confirm_password", PasswordField("Confirm Password"))

    override fun clean() {
        val pwd = get<String>("password")
        val confirm = get<String>("confirm_password")
        
        if (pwd != null && confirm != null && pwd != confirm) {
            throw ValidationError("Passwords do not match")
        }
    }
}
