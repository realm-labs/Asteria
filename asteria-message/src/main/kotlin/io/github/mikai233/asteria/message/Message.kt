package io.github.mikai233.asteria.message

interface Message

interface ShardMessage<ID : Any> : Message {
    val id: ID
}
