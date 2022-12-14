package com.kursor.chroniclesofww2.game

import com.kursor.chroniclesofww2.features.GameFeaturesMessages
import com.kursor.chroniclesofww2.features.GameFeaturesMessages.INVALID_MOVE
import com.kursor.chroniclesofww2.features.GameSessionDTO
import com.kursor.chroniclesofww2.features.GameSessionMessageType
import com.kursor.chroniclesofww2.game.GameSession.MessageHandler
import com.kursor.chroniclesofww2.logging.Log
import com.kursor.chroniclesofww2.model.game.Model
import com.kursor.chroniclesofww2.model.game.RuleManager
import com.kursor.chroniclesofww2.model.game.moves.AddMove
import com.kursor.chroniclesofww2.model.game.moves.MotionMove
import com.kursor.chroniclesofww2.model.game.moves.Move
import com.kursor.chroniclesofww2.model.serializable.GameData
import com.kursor.chroniclesofww2.model.serializable.Player
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


//player 1 always initiator , it is a host of the game
class GameSession(
    val id: Int,
    val initiatorGameData: GameData,
    val isMatch: Boolean = false,
    val onGameSessionStarted: suspend (GameSession) -> Unit = {},
    val onGameSessionStopped: suspend (GameSession) -> Unit = {},
    val onMatchOver: suspend (winner: String, loser: String) -> Unit = { winner, loser -> }
) {

    private val initiatorPlayer = initiatorGameData.me
    private val connectedPlayer = initiatorGameData.enemy

    private val model = Model(initiatorGameData)
    private val ruleManager = RuleManager(model)

    private var initiatorClient: Client? = null
    private var connectedClient: Client? = null


    val clientsInitialized: Boolean
        get() = initiatorClient != null && connectedClient != null

    val messageHandler = MessageHandler { login, receiveDTO ->
        Log.d("GameSession", "$receiveDTO")
        val client = getClientWithLogin(login)!!
        val otherClient = getOtherClientForLogin(login)!!
        val type = receiveDTO.type
        val message = receiveDTO.message
        when (type) {
            GameSessionMessageType.MOVE -> processMoveMessage(client, otherClient, receiveDTO)
            GameSessionMessageType.DISCONNECT -> {
                otherClient.send(
                    GameSessionDTO(
                        type = GameSessionMessageType.DISCONNECT,
                        GameFeaturesMessages.OTHER_PLAYER_DISCONNECTED
                    )
                )
                stopSession()
            }
            else -> Log.i(TAG, "$type:$message")
        }
    }

    init {
        startTimeoutTimer()
    }

    private suspend fun processMoveMessage(client: Client, otherClient: Client, receiveDTO: GameSessionDTO) {
        val move = Move.decodeFromStringToSimplified(receiveDTO.message).restore(ruleManager.model, client.login)
        Log.d("GameSession", "incoming move: $move")
        if (!ruleManager.checkMoveForValidity(move)) {
            client.send(
                gameSessionDTO = GameSessionDTO(
                    type = GameSessionMessageType.ERROR,
                    message = INVALID_MOVE
                )
            )
            return
        }
        otherClient.send(receiveDTO)
        when (move.type) {
            Move.Type.ADD -> model.handleAddMove(move as AddMove)
            Move.Type.MOTION -> model.handleMotionMove(move as MotionMove)
        }
        ruleManager.nextTurn()
        if (isMatch) {
            if (ruleManager.enemyLost()) onMatchOver(
                initiatorPlayer.name,
                connectedPlayer.name
            )
            else if (ruleManager.meLost()) onMatchOver(
                connectedPlayer.name,
                initiatorPlayer.name
            )
        }
    }

    private suspend fun stopSession() {
        initiatorClient?.send(
            GameSessionDTO(
                type = GameSessionMessageType.ERROR,
                message = "stopped session"
            )
        )
        connectedClient?.send(
            GameSessionDTO(
                type = GameSessionMessageType.ERROR,
                message = "stop session"
            )
        )
        initiatorClient?.webSocketSession?.close()
        connectedClient?.webSocketSession?.close()
        onGameSessionStopped(this)
    }

    private fun startTimeoutTimer() {
        CoroutineScope(Dispatchers.IO).launch {
            delay(TIMEOUT)
            if (!clientsInitialized) {
                stopSession()
            }
        }
    }

    fun getPlayerWithName(name: String): Player? = when (name) {
        initiatorPlayer.name -> initiatorPlayer
        connectedPlayer.name -> connectedPlayer
        else -> null
    }

    private fun getClientWithLogin(login: String): Client? = when (login) {
        initiatorClient?.login -> initiatorClient
        connectedClient?.login -> connectedClient
        else -> null
    }

    private fun getOtherClientForLogin(login: String): Client? = when (login) {
        initiatorClient?.login -> connectedClient
        connectedClient?.login -> initiatorClient
        else -> null
    }

    suspend fun initClient(login: String, webSocketServerSession: DefaultWebSocketServerSession) {
        val player = getPlayerWithName(login)
        if (player == null) {
            webSocketServerSession.close(
                CloseReason(
                    CloseReason.Codes.CANNOT_ACCEPT,
                    GameFeaturesMessages.NO_SUCH_PLAYER
                )
            )
            return
        }

        setClient(Client(login, webSocketServerSession))
        tryStart()
    }

    private fun setClient(client: Client) {
        when (client.login) {
            initiatorPlayer.name -> initiatorClient = client
            connectedPlayer.name -> connectedClient = client
        }
    }

    private suspend fun tryStart() {
        if (!clientsInitialized) return
        initiatorClient!!.send(
            GameSessionDTO(
                type = GameSessionMessageType.GAME_EVENT,
                message = GameFeaturesMessages.GAME_STARTED
            )
        )
        connectedClient!!.send(
            GameSessionDTO(
                type = GameSessionMessageType.GAME_EVENT,
                message = GameFeaturesMessages.GAME_STARTED
            )
        )
        onGameSessionStarted(this)
    }


    private fun Move.Simplified.restore(model: Model, login: String): Move {
        val move = when (type) {
            Move.Type.MOTION -> {
                this as MotionMove.Simplified
                val startRow = startCoordinate / 10
                val startColumn = startCoordinate % 10
                val destRow = destinationCoordinate / 10
                val destColumn = destinationCoordinate % 10
                MotionMove(model.board[startRow, startColumn], model.board[destRow, destColumn])
            }
            Move.Type.ADD -> {
                this as AddMove.Simplified
                val destRow = destinationCoordinate / 10
                val destColumn = destinationCoordinate % 10
                val reserve = if (login == initiatorPlayer.name) model.me.divisionResources.reserves[divisionType]!!
                else model.enemy.divisionResources.reserves[divisionType]!!
                AddMove(
                    reserve,
                    model.board[destRow, destColumn]
                )
            }
        }
        return move
    }

    companion object {
        const val TAG = "GameSession"
        const val TIMEOUT = 60000L
    }


    fun interface MessageHandler {
        suspend fun onPlayerMessage(login: String, gameSessionDTO: GameSessionDTO)
    }
}