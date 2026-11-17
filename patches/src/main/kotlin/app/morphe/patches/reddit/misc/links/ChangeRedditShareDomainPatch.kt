/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.links

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Templates for Reddit share-link string literals. The `{domain}` placeholder is replaced with
 * the domain the user types into the patch option, so there is no hardcoded `reddit.com` or
 * `redlib.kareem.one` target — the destination is the user's choice.
 */
private val SHARE_LINK_TEMPLATES = listOf(
    "https://www.{domain}",
    "https://www.{domain}/",
    "https://www.{domain}/r/",
    "https://www.{domain}/u/",
    "https://www.{domain}/user/",
    "https://www.{domain}/u/{username}/comments",
    "https://www.{domain}/user/{username}/comments",
    "https://{domain}",
    "https://{domain}/",
    "https://{domain}/r/",
    "https://{domain}/u/",
    "https://{domain}/user/",
    "https://{domain}/u/{username}/comments",
    "https://{domain}/user/{username}/comments",
    "https://{domain}%s",
    "https://www.{domain}/comments/{link_id}",
    "https://www.{domain}/comments/{link_id}/",
    "https://www.{domain}/comments/{link_id}/{title}",
    "https://www.{domain}/comments/{link_id}/{title}/",
    "https://www.{domain}/comments/{link_id}/{title}/{comment}",
    "https://www.{domain}/comments/{link_id}/{title}/{comment}/",
    "https://www.{domain}/{prefix}/{subreddit_name}/comments/{link_id}",
    "https://www.{domain}/{prefix}/{subreddit_name}/comments/{link_id}/",
    "https://www.{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}",
    "https://www.{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}/",
    "https://www.{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}/{comment}",
    "https://www.{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}/{comment}/",
    "https://{domain}/comments/{link_id}",
    "https://{domain}/comments/{link_id}/",
    "https://{domain}/comments/{link_id}/{title}",
    "https://{domain}/comments/{link_id}/{title}/",
    "https://{domain}/comments/{link_id}/{title}/{comment}",
    "https://{domain}/comments/{link_id}/{title}/{comment}/",
    "https://{domain}/{prefix}/{subreddit_name}/comments/{link_id}",
    "https://{domain}/{prefix}/{subreddit_name}/comments/{link_id}/",
    "https://{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}",
    "https://{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}/",
    "https://{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}/{comment}",
    "https://{domain}/{prefix}/{subreddit_name}/comments/{link_id}/{title}/{comment}/",
    "https://redd.it/{link_id}"            // special case: rewritten to {domain}/comments/{link_id}
)

private const val REDDIT_DOMAIN = "reddit.com"
private const val WWW_REDDIT_DOMAIN = "www.reddit.com"

@Suppress("unused")
val changeRedditShareDomainPatch =
    bytecodePatch(
        name = "Change Reddit share domain",
        description = "Rewrites generated Reddit share links to a custom domain of your choice."
    ) {
        compatibleWith(COMPATIBILITY_REDDIT)

        val customDomainOption = stringOption(
            key = "customDomain",
            default = "",
            title = "Custom domain",
            description = "The domain to rewrite Reddit share links to (for example redlib.kareem.one).",
            required = false
        )

        execute {
            val domain = customDomainOption.value?.trim()?.takeIf { it.isNotEmpty() }
            if (domain == null) {
                // No custom domain set: keep links unchanged (normal reddit.com).
                return@execute
            }

            val replacements = linkedMapOf<String, String>()
            SHARE_LINK_TEMPLATES.forEach { template ->
                val placeholder = "{domain}"
                val hasPlaceholder = template.contains(placeholder)
                val source = if (hasPlaceholder) {
                    // Both reddit.com and www.reddit.com forms of this template.
                    // Templates starting with "https://www." already contain the www prefix,
                    // so only insert "reddit.com". Others need both "reddit.com" and "www.reddit.com".
                    if (template.startsWith("https://www.")) {
                        listOf(template.replace(placeholder, REDDIT_DOMAIN))
                    } else {
                        listOf(
                            template.replace(placeholder, REDDIT_DOMAIN),
                            template.replace(placeholder, WWW_REDDIT_DOMAIN)
                        )
                    }
                } else {
                    listOf(template)
                }
                val target = if (template == "https://redd.it/{link_id}") {
                    "https://$domain/comments/{link_id}"
                } else {
                    template.replace(placeholder, domain)
                }
                source.forEach { replacements[it] = target }
            }

            var replacementCount = 0
            classDefForEach { classDef ->
                val mutableClass = mutableClassDefBy(classDef.type)

                mutableClass.methods.forEach { method ->
                    val instructions = method.implementation?.instructions?.toList() ?: return@forEach

                    instructions.forEachIndexed { index, instruction ->
                        if (instruction.opcode != Opcode.CONST_STRING &&
                            instruction.opcode != Opcode.CONST_STRING_JUMBO
                        ) {
                            return@forEachIndexed
                        }

                        val literal =
                            ((instruction as? ReferenceInstruction)?.reference as? StringReference)
                                ?.string
                                ?: return@forEachIndexed
                        val target = replacements[literal] ?: return@forEachIndexed
                        val register = (instruction as? OneRegisterInstruction)?.registerA ?: return@forEachIndexed
                        val opcode =
                            when (instruction.opcode) {
                                Opcode.CONST_STRING_JUMBO -> "const-string/jumbo"
                                else -> "const-string"
                            }

                        method.replaceInstruction(
                            index,
                            "$opcode v$register, \"${target.escapeSmaliString()}\"",
                        )
                        replacementCount++
                    }
                }
            }

            if (replacementCount == 0) {
                error("Could not rewrite any Reddit share-link strings for domain $domain")
            }
        }
    }

private fun String.escapeSmaliString(): String =
    buildString {
        for (character in this) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
