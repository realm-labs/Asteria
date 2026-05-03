package io.github.realmlabs.asteria.message

interface Message

interface ShardMessage<ID : Any> : Message {
    val id: ID
}
