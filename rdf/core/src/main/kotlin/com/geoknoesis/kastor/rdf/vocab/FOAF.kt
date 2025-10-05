package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * FOAF (Friend of a Friend) vocabulary.
 * A vocabulary for describing people and their relationships.
 * 
 * @see <a href="http://xmlns.com/foaf/spec/">FOAF Specification</a>
 */
object FOAF : Vocabulary {
    override val namespace: String = "http://xmlns.com/foaf/0.1/"
    override val prefix: String = "foaf"
    
    // Core classes
    val Agent: Iri by lazy { term("Agent") }
    val Person: Iri by lazy { term("Person") }
    val Organization: Iri by lazy { term("Organization") }
    val Group: Iri by lazy { term("Group") }
    val Document: Iri by lazy { term("Document") }
    val Image: Iri by lazy { term("Image") }
    val Project: Iri by lazy { term("Project") }
    
    // Core properties
    val name: Iri by lazy { term("name") }
    val firstName: Iri by lazy { term("firstName") }
    val familyName: Iri by lazy { term("familyName") }
    val givenName: Iri by lazy { term("givenName") }
    val surname: Iri by lazy { term("surname") }
    val nick: Iri by lazy { term("nick") }
    val title: Iri by lazy { term("title") }
    val homepage: Iri by lazy { term("homepage") }
    val weblog: Iri by lazy { term("weblog") }
    val openid: Iri by lazy { term("openid") }
    val jabberID: Iri by lazy { term("jabberID") }
    val mbox: Iri by lazy { term("mbox") }
    val mbox_sha1sum: Iri by lazy { term("mbox_sha1sum") }
    val gender: Iri by lazy { term("gender") }
    val birthday: Iri by lazy { term("birthday") }
    val age: Iri by lazy { term("age") }
    val knows: Iri by lazy { term("knows") }
    val based_near: Iri by lazy { term("based_near") }
    val currentProject: Iri by lazy { term("currentProject") }
    val pastProject: Iri by lazy { term("pastProject") }
    val topic: Iri by lazy { term("topic") }
    val topic_interest: Iri by lazy { term("topic_interest") }
    val primaryTopic: Iri by lazy { term("primaryTopic") }
    val made: Iri by lazy { term("made") }
    val maker: Iri by lazy { term("maker") }
    val depiction: Iri by lazy { term("depiction") }
    val depicts: Iri by lazy { term("depicts") }
    val thumbnail: Iri by lazy { term("thumbnail") }
    val img: Iri by lazy { term("img") }
    val logo: Iri by lazy { term("logo") }
    val member: Iri by lazy { term("member") }
    val membershipClass: Iri by lazy { term("membershipClass") }
    val focus: Iri by lazy { term("focus") }
    val fundedBy: Iri by lazy { term("fundedBy") }
    val theme: Iri by lazy { term("theme") }
    val schoolHomepage: Iri by lazy { term("schoolHomepage") }
    val workInfoHomepage: Iri by lazy { term("workInfoHomepage") }
    val workplaceHomepage: Iri by lazy { term("workplaceHomepage") }
    val accountName: Iri by lazy { term("accountName") }
    val accountServiceHomepage: Iri by lazy { term("accountServiceHomepage") }
    val holdsAccount: Iri by lazy { term("holdsAccount") }
    val phone: Iri by lazy { term("phone") }
    val aimChatID: Iri by lazy { term("aimChatID") }
    val skypeID: Iri by lazy { term("skypeID") }
    val icqChatID: Iri by lazy { term("icqChatID") }
    val yahooChatID: Iri by lazy { term("yahooChatID") }
    val msnChatID: Iri by lazy { term("msnChatID") }
    val status: Iri by lazy { term("status") }
    val publications: Iri by lazy { term("publications") }
    val geekcode: Iri by lazy { term("geekcode") }
    val dnaChecksum: Iri by lazy { term("dnaChecksum") }
    val plan: Iri by lazy { term("plan") }
    val sha1: Iri by lazy { term("sha1") }
    val interest: Iri by lazy { term("interest") }
    val tipjar: Iri by lazy { term("tipjar") }
    val myersBriggs: Iri by lazy { term("myersBriggs") }
}
