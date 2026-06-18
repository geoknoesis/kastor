package com.geoknoesis.kastor.gen.processor.utils

import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnumNamingTest {
    @Test fun `upper snake from simple word`() = assertEquals("DRAFT", NamingUtils.toEnumConstant("DRAFT"))
    @Test fun `upper snake from kebab`() = assertEquals("IN_PROGRESS", NamingUtils.toEnumConstant("in-progress"))
    @Test fun `upper snake from camel`() = assertEquals("IN_PROGRESS", NamingUtils.toEnumConstant("inProgress"))
    @Test fun `leading digit is prefixed`() = assertEquals("_2FA", NamingUtils.toEnumConstant("2fa"))
}
