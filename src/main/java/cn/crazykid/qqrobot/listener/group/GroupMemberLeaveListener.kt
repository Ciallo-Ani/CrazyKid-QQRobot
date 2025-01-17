package cn.crazykid.qqrobot.listener.group

import cc.moecraft.icq.event.EventHandler
import cc.moecraft.icq.event.IcqListener
import cc.moecraft.icq.event.events.notice.groupmember.decrease.EventNoticeGroupMemberLeave
import cc.moecraft.icq.sender.message.MessageBuilder
import org.springframework.stereotype.Component

/**
 * 群成员主动退群事件监听
 *
 * @author CrazyKid
 * @date 2021/1/14 16:14
 */
@Component
class GroupMemberLeaveListener : IcqListener() {
    @EventHandler
    fun onEvent(event: EventNoticeGroupMemberLeave) {
        val messageBuilder = MessageBuilder()
        messageBuilder.add(event.userId).add(" 退群了")
        event.httpApi.sendGroupMsg(event.groupId, messageBuilder.toString())
    }
}
