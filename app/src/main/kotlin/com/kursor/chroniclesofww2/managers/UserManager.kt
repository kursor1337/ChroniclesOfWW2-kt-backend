package com.kursor.chroniclesofww2.managers

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kursor.chroniclesofww2.Variables.JWT_SECRET
import com.kursor.chroniclesofww2.entities.User
import com.kursor.chroniclesofww2.features.*
import com.kursor.chroniclesofww2.features.LoginErrorMessages.INCORRECT_PASSWORD
import com.kursor.chroniclesofww2.features.LoginErrorMessages.NO_SUCH_USER
import com.kursor.chroniclesofww2.features.RegisterErrorMessages.USER_ALREADY_REGISTERED
import com.kursor.chroniclesofww2.features.UserInfoMessages.SUCCESS
import com.kursor.chroniclesofww2.repositories.UserRepository
import org.mindrot.jbcrypt.BCrypt

class UserManager(val userRepository: UserRepository) {

    suspend fun getAllUsers(): List<User> {
        return userRepository.getAllUsers()
    }

    suspend fun getUserByLogin(login: String): User? = userRepository.getUserByLogin(login)

    suspend fun loginUser(loginReceiveDTO: LoginReceiveDTO): LoginResponseDTO {
        val user = userRepository.getUserByLogin(loginReceiveDTO.login)
            ?: return LoginResponseDTO(
                token = null,
                expiresIn = 0L,
                message = NO_SUCH_USER
            )

        if (!BCrypt.checkpw(loginReceiveDTO.password, user.passwordHash)) {
            return LoginResponseDTO(
                token = null,
                expiresIn = 0L,
                message = INCORRECT_PASSWORD
            )
        }
        val token = TokenManager.generateToken(user)

        return LoginResponseDTO(
            token = token,
            message = SUCCESS,
            expiresIn = TokenManager.TOKEN_LIFETIME
        )
    }

    suspend fun registerUser(registerReceiveDTO: RegisterReceiveDTO): RegisterResponseDTO {
        if (userRepository.getUserByLogin(registerReceiveDTO.login) != null) return RegisterResponseDTO(
            token = null,
            expiresIn = 0L,
            message = USER_ALREADY_REGISTERED
        )
        val passwordHash = BCrypt.hashpw(registerReceiveDTO.password, BCrypt.gensalt())
        val user = User(registerReceiveDTO.login, registerReceiveDTO.username, passwordHash)
        userRepository.saveUser(user)
        val token = TokenManager.generateToken(user)
        return RegisterResponseDTO(
            token = token,
            expiresIn = 0L,
            message = SUCCESS
        )
    }

    suspend fun updateUserInfo(login: String, newUserInfo: UserInfo): UpdateUserInfoResponseDTO {
        val user = userRepository.getUserByLogin(login) ?: return UpdateUserInfoResponseDTO(message = UserInfoMessages.NO_SUCH_USER)
        userRepository.updateUser(User(login, newUserInfo.username, user.passwordHash))
        return UpdateUserInfoResponseDTO(message = SUCCESS)
    }

    suspend fun changePasswordForUser(
        login: String,
        changePasswordReceiveDTO: ChangePasswordReceiveDTO
    ): ChangePasswordResponseDTO {
        val user = userRepository.getUserByLogin(login)
            ?: return ChangePasswordResponseDTO(
                token = null,
                message = NO_SUCH_USER
            )
        val newPassword = changePasswordReceiveDTO.newPassword
        userRepository.updateUser(User(login, user.username, BCrypt.hashpw(newPassword, BCrypt.gensalt())))
        val newToken = TokenManager.generateToken(user)
        return ChangePasswordResponseDTO(token = newToken, message = SUCCESS)
    }

    suspend fun deleteUser(login: String, deleteUserReceiveDTO: DeleteUserReceiveDTO): DeleteUserResponseDTO {
        if (userRepository.getUserByLogin(login) == null) return DeleteUserResponseDTO(NO_SUCH_USER)
        userRepository.deleteUser(login)
        return DeleteUserResponseDTO(message = SUCCESS)
    }
}