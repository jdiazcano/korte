package com.soywiz.korte.filter

import com.soywiz.korio.util.Dynamic
import com.soywiz.korte.Filter
import com.soywiz.korte.toDynamicString

val FilterLower = Filter("lower") { subject, _ -> subject.toDynamicString().toLowerCase() }