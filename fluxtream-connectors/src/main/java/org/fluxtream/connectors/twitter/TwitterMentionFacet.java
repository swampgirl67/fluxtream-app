package org.fluxtream.connectors.twitter;

import org.fluxtream.core.connectors.annotations.ObjectTypeSpec;
import org.fluxtream.core.domain.AbstractFacet;

import javax.persistence.Entity;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;

@Entity(name="Facet_TwitterMention")
@ObjectTypeSpec(name = "mention", value = 4, extractor=TwitterFacetExtractor.class, parallel=true, prettyname = "Mentions")
@NamedQueries({
		@NamedQuery(name = "twitter.mention.smallestTwitterId", query = "SELECT facet FROM Facet_TwitterMention facet WHERE facet.guestId=? ORDER BY facet.twitterId ASC LIMIT 1"),
		@NamedQuery(name = "twitter.mention.biggestTwitterId", query = "SELECT facet FROM Facet_TwitterMention facet WHERE facet.guestId=? ORDER BY facet.twitterId DESC LIMIT 1")
})
public class TwitterMentionFacet extends AbstractFacet {

	public long twitterId;
	
	public String text;
	public long time;

	public String profileImageUrl;

	public String userName;
    public String name;

    public TwitterMentionFacet() {
        super();
    }

    public TwitterMentionFacet(final long apiKeyId) {
        super(apiKeyId);
    }

    @Override
	protected void makeFullTextIndexable() {
		this.fullTextDescription = text;
	}
	
}