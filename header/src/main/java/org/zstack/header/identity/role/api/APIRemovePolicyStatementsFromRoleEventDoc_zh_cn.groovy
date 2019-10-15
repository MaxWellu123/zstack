package org.zstack.header.identity.role.api

import org.zstack.header.errorcode.ErrorCode

doc {

	title "从角色移除权限描述的结果"

	ref {
		name "error"
		path "org.zstack.header.identity.role.api.APIRemovePolicyStatementsFromRoleEvent.error"
		desc "错误码，若不为null，则表示操作失败, 操作成功时该字段为null",false
		type "ErrorCode"
		since "0.6"
		clz ErrorCode.class
	}
}
