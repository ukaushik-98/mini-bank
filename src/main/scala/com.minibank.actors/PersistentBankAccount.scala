package `com.minibank.actors`

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.PersistenceId

// a single bank account
class PersistentBankAccount {

  // commands = messages
  sealed trait Command
  case class CreateBankAccount(
      user: String,
      currency: String,
      initialBalance: Double,
      replyTo: ActorRef[Response]
  ) extends Command
  case class UpdateBalance(
      id: String,
      currency: String,
      amount: Double /* can be < 0*/,
      replyTo: ActorRef[Response]
  ) extends Command
  case class GetBankAccount(id: String, replyTo: ActorRef[Response])
      extends Command

  // events = to persist to Cassandra
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Double) extends Event

  // state
  case class BankAccount(
      id: String,
      user: String,
      currency: String,
      balance: Double
  )

  // responses
  sealed trait Response
  case class BankAccountCreatedResponse(id: String) extends Response
  case class BankAccountBalanceUpdatedResponse(
      maybeBankAccount: Option[BankAccount]
  ) extends Response
  case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount])
      extends Response

  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] =
    (state, command) =>
      command match {
        case CreateBankAccount(user, currency, initialBalance, bank) =>
          val id = state.id
          Effect
            .persist(
              BankAccountCreated(
                BankAccount(id, user, currency, initialBalance)
              )
            ) // persisted into Cassandra
            .thenReply(bank)(_ => BankAccountCreatedResponse(id))
        case UpdateBalance(_, _, amount, bank) =>
          val newBalance = state.balance + amount
          if (newBalance < 0)
            Effect.reply(bank)(BankAccountBalanceUpdatedResponse(None))
          else
            Effect
              .persist(BalanceUpdated(amount))
              .thenReply(bank)(newState =>
                BankAccountBalanceUpdatedResponse(Some(newState))
              )
        case GetBankAccount(_, bank) =>
          Effect.reply(bank)(GetBankAccountResponse(Some(state)))
      }

  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) => bankAccount
      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), // unused
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
