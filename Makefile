years = $(shell seq 2010 "$$(date +%Y)")

.PHONY: all
all: $(years)

.PHONY: $(years)
$(years)::
	year=$$(echo -n $@ | tail --bytes 2); wget --directory-prefix $@ \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-d1/5000-d1-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-d2/5000-d2-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s1/5000-s1-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s11/5000-s11-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s13/5000-s13-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s2-8/5000-s2-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s2-8/5000-s8-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s2/5000-s2-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s3/5000-s3-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s4-5/5000-s4-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s4-5/5000-s5-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s4/5000-s4-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s5/5000-s5-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s6/5000-s6-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s7/5000-s7-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s8-9/5000-s8-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s8-9/5000-s9-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s8/5000-s8-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5000-s9/5000-s9-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-c-s2/5010-c-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-c/5010-c-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-d/5010-d-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-r/5010-r-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-s11/5010-s11-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-s12/5010-s12-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-s2/5010-s2-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/5010-tc/5010-tc-$${year}e.txt" \
	  "https://www.canada.ca/content/dam/cra-arc/formspubs/pbg/t2204/t2204-$${year}e.txt" \
	  "https://www.canada.ca/en/revenue-agency/services/child-family-benefits/goods-services-tax-harmonized-sales-tax-gst-hst-credit/goods-services-tax-harmonized-sales-tax-credit-calculation-sheet-july-$$(($@ + 1))-june-$$(($@ + 2))-payments-$@-tax-year.html" \
	  || true

2018::
	wget --directory-prefix $@ \
	  https://www.canada.ca/en/revenue-agency/services/child-family-benefits/goods-services-tax-harmonized-sales-tax-gst-hst-credit/cvd-19-cal-sheet.html \
	  || true
